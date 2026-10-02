package dev.localphoto.enhancer.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.PowerManager
import android.util.Log
import dev.localphoto.core.*
import dev.localphoto.enhancer.data.*
import dev.localphoto.enhancer.BuildConfig
import dev.localphoto.enhancer.processing.ProcessingControl
import dev.localphoto.enhancer.processing.ProcessingStopped
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

private class ThermalTileReduction : RuntimeException()

class EnhancementEngine(
    private val context: Context,
    private val repository: ProjectRepository,
    private val models: ModelManager,
    private val exporter: GalleryExporter,
) {
    private val files = repository.files
    private val dao = repository.dao
    private val powerManager = context.getSystemService(PowerManager::class.java)

    suspend fun process(project: PhotoProject, control: ProcessingControl) {
        var bitmap: Bitmap? = null
        var completed = false
        var pendingResult: File? = null
        try {
            control.check()
            val capabilities = DeviceCapabilityDetector.detect(context)
            val checkpoint = project.checkpointPath
                ?.let { files.owned(File(it)) }
                ?.takeIf { it.isFile }
            val persisted = project.planJson.takeIf { it.isNotBlank() }?.let(::readPersistedPlan)
            val settings = persisted?.settings ?: project.settings
            val source = files.owned(File(project.basePath ?: project.sourcePath))
            val originalDimensions = files.dimensions(source)

            update(project.id, Stage.DECODE, project.progress)
            var notice = project.noticeCode
            bitmap = if (checkpoint != null) {
                try { files.decodeResult(checkpoint) }
                catch (failure: PhotoFailure) {
                    if (failure.code == "memory") throw PhotoFailure("resume_memory", failure)
                    throw failure
                }
            } else {
                val decoded = files.decode(source, capabilities.maxSourcePixels)
                val sampled = decoded.width < originalDimensions.first || decoded.height < originalDimensions.second
                if (sampled) notice = addNotice(notice, "source_sampled")
                val transformed = files.transform(decoded, settings.transform)
                if (transformed !== decoded) decoded.recycle()
                transformed
            }

            val previewWidth = min(384, bitmap.width)
            val previewHeight = (bitmap.height * previewWidth.toFloat() / bitmap.width)
                .toInt().coerceAtLeast(1)
            val preview = Bitmap.createScaledBitmap(bitmap, previewWidth, previewHeight, true)
            val pixels = IntArray(preview.width * preview.height)
            preview.getPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)
            val metrics = ImageAnalyzer.analyze(pixels, preview.width, preview.height)
                .copy(width = bitmap.width, height = bitmap.height)
            if (preview !== bitmap) preview.recycle()
            update(project.id, Stage.ANALYZE, if (checkpoint == null) 0.05f else project.progress)

            var plan: PipelinePlan
            var stages: List<Stage>
            if (persisted != null) {
                stages = persisted.stages
                plan = PipelinePlan(stages, persisted.settings.adjustments, persisted.detected)
            } else {
                plan = PipelinePlanner.plan(metrics, settings)
                stages = processingStages(plan.stages)
            }

            val startingStage = if (checkpoint != null) project.checkpointStage + 1 else 0
            val superResolutionRemaining = stages.drop(startingStage).contains(Stage.SUPER_RESOLUTION)
            val scaleForMemory = if (superResolutionRemaining) plan.adjustments.scale else 1
            val memory = MemoryPolicy.assess(
                bitmap.width,
                bitmap.height,
                scaleForMemory,
                capabilities.processingBudget,
            )
            if (!memory.isSafe) throw PhotoFailure(if (persisted != null) "resume_memory" else "memory")
            if (persisted != null && memory.safeScale < scaleForMemory) {
                throw PhotoFailure("resume_memory")
            }
            if (persisted == null && memory.safeScale < plan.adjustments.scale) {
                notice = addNotice(notice, "scale_reduced")
                plan = plan.copy(adjustments = plan.adjustments.copy(scale = memory.safeScale))
                if (memory.safeScale == 1) stages = stages.filter { it != Stage.SUPER_RESOLUTION }
            }

            if (persisted == null) {
                val savedSettings = settings.copy(auto = false, adjustments = plan.adjustments)
                val json = JSONObject().apply {
                    put("settings", JSONObject(SettingsCodec.encode(savedSettings)))
                    put("stages", JSONArray(stages.map { it.name }))
                    put("detected", JSONArray(plan.detected.map { it.name }))
                }
                dao.find(project.id)?.let {
                    dao.update(it.copy(planJson = json.toString(), noticeCode = notice))
                }
            }

            for (index in startingStage until stages.size) {
                checkWorkUnit(control, tileSize = 64)
                val stage = stages[index]
                if (BuildConfig.DEBUG) Log.d("LocalPhotoEngine", "stage=${stage.name}")
                update(project.id, stage, stageProgress(index, 0f, stages.size))
                val current = requireNotNull(bitmap) { "Decoded bitmap was released before stage processing" }
                val parameters = parametersFor(stage, plan.adjustments)
                val requestedTileSize = DeviceCapabilityDetector.detect(context).tileSize(settings.profile)
                val next = processStage(
                    stage = stage,
                    source = current,
                    adjustments = parameters,
                    scale = plan.adjustments.scale,
                    initialTileSize = requestedTileSize,
                    control = control,
                ) { fraction ->
                    update(project.id, stage, stageProgress(index, fraction, stages.size))
                }
                bitmap = next
                current.recycle()
                control.check()

                val stageFile = File(files.directory(project.id), "temporary/stage-$index.png")
                files.atomicPng(bitmap, stageFile)
                val previous = dao.find(project.id)
                if (previous != null) {
                    dao.update(
                        previous.copy(
                            checkpointPath = stageFile.absolutePath,
                            checkpointStage = index,
                            progress = stageProgress(index, 1f, stages.size),
                        ),
                    )
                    previous.checkpointPath
                        ?.takeIf { it != stageFile.absolutePath }
                        ?.let { files.owned(File(it)).delete() }
                }
            }

            control.check()
            update(project.id, Stage.EXPORT, 0.95f)
            val revision = project.revision + 1
            val result = File(files.directory(project.id), "results/result-$revision.png")
            pendingResult = result
            files.atomicPng(bitmap, result)
            control.check()
            var current = dao.find(project.id) ?: throw PhotoFailure("export")
            current = current.copy(
                status = ProjectStatus.COMPLETED.name,
                outputPath = result.absolutePath,
                outputWidth = bitmap.width,
                outputHeight = bitmap.height,
                revision = revision,
                progress = 1f,
                checkpointPath = null,
                checkpointStage = -1,
                errorCode = null,
                noticeCode = notice,
                updatedAt = System.currentTimeMillis(),
            )
            dao.update(current)
            completed = true
            pendingResult = null
            try {
                val uri = exporter.export(current, bitmap, settings.output)
                dao.recordGalleryExport(project.id, revision, result.absolutePath, uri.toString())
            } catch (_: Exception) {
                dao.recordGalleryFailure(project.id, revision, result.absolutePath)
            }
            repository.removeSettledTemporary(current)
        } catch (failure: Throwable) {
            if (completed) return
            pendingResult?.let { runCatching { files.owned(it).delete() } }
            val stopped = runCatching { control.check() }.exceptionOrNull() as? ProcessingStopped
            val interrupted = (failure as? ProcessingStopped)?.interrupted ?: stopped?.interrupted
            val code = when {
                failure is ProcessingStopped -> if (failure.interrupted) "interrupted" else null
                stopped != null -> if (stopped.interrupted) "interrupted" else null
                failure is PhotoFailure -> failure.code
                failure is OutOfMemoryError || failure is TileMemoryPressure -> "memory"
                else -> "inference"
            }
            val status = when {
                interrupted == true -> ProjectStatus.INTERRUPTED
                failure is PhotoFailure && failure.code == "resume_memory" -> ProjectStatus.INTERRUPTED
                failure is ProcessingStopped || stopped != null -> ProjectStatus.CANCELLED
                else -> ProjectStatus.FAILED
            }
            val current = dao.find(project.id)
            if (BuildConfig.DEBUG) Log.d("LocalPhotoEngine", "stopped status=${status.name} code=$code")
            if (current != null) {
                val settled = current.copy(
                        status = status.name,
                        errorCode = code,
                        checkpointPath = if (status == ProjectStatus.INTERRUPTED) current.checkpointPath else null,
                        checkpointStage = if (status == ProjectStatus.INTERRUPTED) current.checkpointStage else -1,
                        updatedAt = System.currentTimeMillis(),
                    )
                dao.update(settled)
                if (status != ProjectStatus.INTERRUPTED) repository.removeSettledTemporary(settled)
            }
            if (status == ProjectStatus.INTERRUPTED) throw ProcessingStopped(true)
        } finally {
            bitmap?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    private suspend fun processStage(
        stage: Stage,
        source: Bitmap,
        adjustments: Adjustments,
        scale: Int,
        initialTileSize: Int,
        control: ProcessingControl,
        progress: suspend (Float) -> Unit,
    ): Bitmap {
        var tileSize = initialTileSize.coerceAtLeast(32)
        while (true) {
            try {
                val beforeWork = { checkWorkUnit(control, tileSize) }
                return if (stage == Stage.SUPER_RESOLUTION) {
                    TiledSuperResolution(models).upscale(
                        source,
                        scale,
                        tileSize,
                        control,
                        beforeWork,
                        progress,
                    )
                } else {
                    applyTiles(source, adjustments, tileSize, control, beforeWork, progress)
                }
            } catch (_: ThermalTileReduction) {
                if (tileSize <= 64) {
                    control.stop(pause = true)
                    control.check()
                }
                tileSize = 64
                progress(0f)
            } catch (failure: TileMemoryPressure) {
                if (tileSize <= 32) throw PhotoFailure("memory", failure)
                tileSize = max(32, tileSize / 2)
                progress(0f)
            } catch (failure: OutOfMemoryError) {
                if (tileSize <= 32) throw PhotoFailure("memory", failure)
                tileSize = max(32, tileSize / 2)
                progress(0f)
            }
        }
    }

    private fun checkWorkUnit(control: ProcessingControl, tileSize: Int) {
        control.check()
        val thermal = powerManager.currentThermalStatus
        if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) {
            control.stop(pause = true)
            control.check()
        }
        if (thermal >= PowerManager.THERMAL_STATUS_MODERATE && tileSize > 64) {
            throw ThermalTileReduction()
        }
    }

    private suspend fun update(id: String, stage: Stage, progress: Float) {
        val project = dao.find(id) ?: return
        if (project.state == ProjectStatus.CANCELLED) return
        dao.update(project.copy(stage = stage.name, progress = progress.coerceIn(0f, 1f)))
    }

    private suspend fun applyTiles(
        source: Bitmap,
        settings: Adjustments,
        tileSize: Int,
        control: ProcessingControl,
        beforeWork: () -> Unit,
        progress: suspend (Float) -> Unit,
    ): Bitmap {
        val output = try {
            Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        } catch (failure: OutOfMemoryError) {
            throw PhotoFailure("memory", failure)
        }
        try {
            val tiles = TilePlanner.tiles(source.width, source.height, tileSize, 8)
            for ((index, tile) in tiles.withIndex()) {
                control.check()
                beforeWork()
                try {
                    val pixels = IntArray(tile.width * tile.height)
                    source.getPixels(pixels, 0, tile.width, tile.left, tile.top, tile.width, tile.height)
                    val processed = PixelProcessor.process(pixels, tile.width, tile.height, settings)
                    output.setPixels(
                        processed,
                        (tile.coreTop - tile.top) * tile.width + tile.coreLeft - tile.left,
                        tile.width,
                        tile.coreLeft,
                        tile.coreTop,
                        tile.coreWidth,
                        tile.coreHeight,
                    )
                } catch (failure: OutOfMemoryError) {
                    throw TileMemoryPressure(failure)
                }
                progress((index + 1f) / tiles.size)
            }
            return output
        } catch (failure: Throwable) {
            output.recycle()
            throw failure
        }
    }

    private fun parametersFor(stage: Stage, adjustments: Adjustments): Adjustments {
        val neutral = Adjustments(
            scale = 1,
            sharpen = 0f,
            detailPreservation = adjustments.detailPreservation,
        )
        return when (stage) {
            Stage.DENOISE -> neutral.copy(denoise = adjustments.denoise)
            Stage.DEBLUR -> neutral.copy(deblur = adjustments.deblur)
            Stage.LIGHTING -> neutral.copy(
                exposure = adjustments.exposure,
                brightness = adjustments.brightness,
                contrast = adjustments.contrast,
                highlights = adjustments.highlights,
                shadows = adjustments.shadows,
                whitePoint = adjustments.whitePoint,
                blackPoint = adjustments.blackPoint,
                gamma = adjustments.gamma,
            )
            Stage.COLOR -> neutral.copy(
                temperature = adjustments.temperature,
                tint = adjustments.tint,
                saturation = adjustments.saturation,
                vibrance = adjustments.vibrance,
                hue = adjustments.hue,
                redBalance = adjustments.redBalance,
                greenBalance = adjustments.greenBalance,
                blueBalance = adjustments.blueBalance,
                fadedColor = adjustments.fadedColor,
            )
            Stage.REFINE -> neutral.copy(sharpen = adjustments.sharpen)
            else -> neutral
        }
    }

    private fun readPersistedPlan(json: String): PersistedPlan {
        try {
            val root = JSONObject(json)
            val settings = SettingsCodec.decode(root.getJSONObject("settings").toString())
            val stageArray = root.getJSONArray("stages")
            val stages = (0 until stageArray.length()).map { Stage.valueOf(stageArray.getString(it)) }
            val detectedArray = root.optJSONArray("detected") ?: JSONArray()
            val detected = (0 until detectedArray.length()).mapTo(linkedSetOf()) {
                Defect.valueOf(detectedArray.getString(it))
            }
            if (stages.any { it in setOf(Stage.DECODE, Stage.ANALYZE, Stage.EXPORT) }) {
                throw IllegalArgumentException("Stored plan contains lifecycle stage")
            }
            return PersistedPlan(settings, stages, detected)
        } catch (failure: Exception) {
            throw PhotoFailure("inference", failure)
        }
    }

    private fun processingStages(stages: List<Stage>): List<Stage> = stages
        .distinct()
        .filter { it !in setOf(Stage.DECODE, Stage.ANALYZE, Stage.EXPORT) }

    private fun stageProgress(index: Int, fraction: Float, count: Int): Float =
        0.1f + (index + fraction.coerceIn(0f, 1f)) / (count + 1f) * 0.8f

    private fun addNotice(existing: String?, addition: String): String = when {
        existing == addition -> addition
        existing == "source_sampled" && addition == "scale_reduced" -> "sampled_and_scale"
        existing == "scale_reduced" && addition == "source_sampled" -> "sampled_and_scale"
        existing == "sampled_and_scale" -> existing
        else -> addition
    }

    private data class PersistedPlan(
        val settings: EnhanceSettings,
        val stages: List<Stage>,
        val detected: Set<Defect>,
    )
}
