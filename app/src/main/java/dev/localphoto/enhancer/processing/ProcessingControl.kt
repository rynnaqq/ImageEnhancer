package dev.localphoto.enhancer.processing

import ai.onnxruntime.OrtSession
import java.util.concurrent.atomic.AtomicBoolean

class ProcessingStopped(val interrupted: Boolean) : RuntimeException()

class ProcessingControl {
    private val stopped = AtomicBoolean(false)
    @Volatile var interrupted: Boolean = false
        private set
    private var runOptions: OrtSession.RunOptions? = null

    @Synchronized fun bind(options: OrtSession.RunOptions?) {
        runOptions = options
        if (stopped.get()) options?.setTerminate(true)
    }

    @Synchronized fun release(options: OrtSession.RunOptions) {
        if (runOptions === options) runOptions = null
    }

    @Synchronized fun stop(pause: Boolean = false) {
        if (pause) interrupted = true
        stopped.set(true)
        runCatching { runOptions?.setTerminate(true) }
    }

    fun isStopped(): Boolean = stopped.get()
    fun check() { if (stopped.get()) throw ProcessingStopped(interrupted) }
}
