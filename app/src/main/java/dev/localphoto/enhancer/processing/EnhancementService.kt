package dev.localphoto.enhancer.processing

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.localphoto.core.ProjectStatus
import dev.localphoto.enhancer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class EnhancementService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var notificationJob: Job? = null
    @Volatile private var cancelQueuedJob: Job? = null
    @Volatile private var control: ProcessingControl? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var finishing = false
    private val cancelAllRequested = AtomicBoolean(false)
    private val retiring = AtomicBoolean(false)
    private val graph get() = (application as EnhancerApp).graph

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.processing_channel), NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (retiring.get()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == CANCEL_CURRENT) {
            control?.stop()
            if (worker?.isActive != true) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == CANCEL_ALL) {
            cancelAllRequested.set(true)
            control?.stop()
            cancelQueuedJob = scope.launch {
                graph.repository.dao.cancelQueued()
                if (worker?.isActive != true) stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        if (worker?.isActive == true) return START_NOT_STICKY
        finishing = false
        cancelAllRequested.set(false)
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        // The three-argument platform API exists at minSdk 29. Pinned Core 1.15's
        // ServiceCompat masks out the newer API 35 mediaProcessing type.
        startForeground(NOTIFICATION_ID, notification(getString(R.string.preparing), 0), type)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:processing").apply { acquire(6 * 60 * 60 * 1000L) }
        notificationJob = scope.launch {
            graph.repository.projects.collect { projects ->
                val active = projects.firstOrNull { it.state == ProjectStatus.PROCESSING }
                if (active != null) {
                    val percent = (active.progress * 100).toInt()
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
                        notification(getString(R.string.processing_notification, active.name, percent), percent))
                }
            }
        }
        worker = scope.launch {
            try {
                graph.recovered.first { it }
                while (isActive && !cancelAllRequested.get() && !retiring.get()) {
                    val project = graph.repository.dao.nextQueued() ?: break
                    if (cancelAllRequested.get() || retiring.get()) break
                    val token = ProcessingControl()
                    control = token
                    try {
                        if (cancelAllRequested.get() || retiring.get()) break
                        // A cancellation may have invalidated nextQueued's snapshot.
                        if (graph.repository.dao.claimQueued(project.id) == 0) continue
                        if (retiring.get()) token.stop(pause = true)
                        else if (cancelAllRequested.get()) token.stop()
                        graph.engine.process(project, token)
                    }
                    catch (_: ProcessingStopped) { break }
                    finally { control = null }
                }
            } finally {
                finishing = true
                notificationJob?.cancel()
                ServiceCompat.stopForeground(this@EnhancementService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** API 35 supplies only a short grace period: pause at the last committed stage and stop. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        retireWorker()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        retireWorker()
        wakeLock?.takeIf { it.isHeld }?.release()
        notificationJob?.cancel()
        // Do not cancel the engine here: its active RunOptions was terminated above and it
        // still needs this scope briefly to persist the last complete stage as interrupted.
        val settlingJobs = listOfNotNull(worker, cancelQueuedJob).filter { !it.isCompleted }
        if (settlingJobs.isEmpty()) {
            scope.cancel()
        } else {
            val remaining = AtomicInteger(settlingJobs.size)
            settlingJobs.forEach { job ->
                job.invokeOnCompletion {
                    if (remaining.decrementAndGet() == 0) scope.cancel()
                }
            }
        }
        super.onDestroy()
    }

    private fun retireWorker() {
        // Set this before inspecting the token: a suspended queue read must never
        // begin another item after timeout/destruction, even if no item was active.
        retiring.set(true)
        val active = control
        if (!finishing) active?.stop(pause = true)
        if (active == null) worker?.cancel()
    }

    private fun notification(text: String, percent: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, EnhancementService::class.java).setAction(CANCEL_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.enhancing_photos)).setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, percent, percent == 0)
            .addAction(0, getString(R.string.cancel_queue), cancel).build()
    }

    companion object {
        private const val CHANNEL = "enhancement"
        private const val NOTIFICATION_ID = 100
        private const val CANCEL_CURRENT = "dev.localphoto.CANCEL_CURRENT"
        private const val CANCEL_ALL = "dev.localphoto.CANCEL_ALL"
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, EnhancementService::class.java))
        fun cancel(context: Context, all: Boolean) {
            context.startService(Intent(context, EnhancementService::class.java).setAction(if (all) CANCEL_ALL else CANCEL_CURRENT))
        }
    }
}
