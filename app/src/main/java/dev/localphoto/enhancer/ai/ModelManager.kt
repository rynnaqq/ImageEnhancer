package dev.localphoto.enhancer.ai

import android.content.Context
import android.util.Log
import ai.onnxruntime.*
import ai.onnxruntime.providers.NNAPIFlags
import dev.localphoto.enhancer.data.PhotoFailure
import dev.localphoto.enhancer.BuildConfig
import dev.localphoto.enhancer.processing.ProcessingControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.EnumSet
import kotlin.math.min

data class ModelState(val ready: Boolean = false, val requiredBytes: Long = 240078,
    val backend: String = "CPU", val probeMilliseconds: Double? = null, val error: String? = null)

class ModelManager(private val context: Context) {
    val environment: OrtEnvironment = OrtEnvironment.getEnvironment().also { environment ->
        // The application is offline and emits no runtime analytics. Some vendor builds may
        // not expose this native switch, so inability to disable an already absent channel is safe.
        runCatching { environment.setTelemetry(false) }
    }
    private val mutableState = MutableStateFlow(ModelState())
    val state = mutableState.asStateFlow()
    private var verifiedFile: File? = null
    private var selectedBackend: String? = null

    @Synchronized
    fun prepare(): File {
        val manifest = JSONObject(context.assets.open("models/manifest.json").bufferedReader().use { it.readText() })
        val model = manifest.getJSONArray("models").getJSONObject(0)
        val bytes = model.getLong("bytes")
        val expected = model.getString("sha256").lowercase()
        mutableState.value = mutableState.value.copy(requiredBytes = bytes)
        verifiedFile?.let { cached ->
            if (cached.isFile && cached.length() == bytes && sha256(cached) == expected) return cached
            verifiedFile = null
        }
        val capabilities = DeviceCapabilityDetector.detect(context)
        if (capabilities.storageBytes < bytes * 2 + 32L * 1024 * 1024) throw PhotoFailure("storage")
        val directory = File(context.filesDir, "models")
        if (!directory.isDirectory && !directory.mkdirs()) throw PhotoFailure("storage")
        val destination = File(directory, model.getString("file"))
        if (!destination.isFile || destination.length() != bytes || sha256(destination) != expected) {
            val temporary = File(directory, destination.name + ".part")
            try {
                context.assets.open("models/${destination.name}").use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                if (temporary.length() != bytes || sha256(temporary) != expected) throw PhotoFailure("model_integrity")
                try {
                    Files.move(
                        temporary.toPath(),
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (failure: Exception) {
                    throw PhotoFailure("storage", failure)
                }
            } finally { temporary.delete() }
        }
        verifiedFile = destination
        mutableState.value = mutableState.value.copy(ready = true, error = null)
        return destination
    }

    /** Run the actual bundled graph with each available provider; cache the fastest usable path. */
    @Synchronized
    fun openSession(control: ProcessingControl? = null): ModelSession {
        control?.check()
        val file = prepare()
        if (selectedBackend == null) {
            val cpu = try {
                probe(file, "CPU", control)
            } catch (failure: Exception) {
                control?.check()
                throw PhotoFailure("model_init", failure)
            }
            var best = "CPU" to cpu.nanoseconds
            for (backend in listOf("XNNPACK", "NNAPI")) {
                control?.check()
                try {
                    val candidate = probe(file, backend, control)
                    if (outputsAgree(cpu.samples, candidate.samples) && candidate.nanoseconds < best.second) {
                        best = backend to candidate.nanoseconds
                    }
                } catch (_: Exception) {
                    control?.check()
                }
            }
            selectedBackend = best.first
            mutableState.value = mutableState.value.copy(
                backend = best.first,
                probeMilliseconds = best.second / 1_000_000.0,
            )
            if (BuildConfig.DEBUG) Log.d("LocalPhotoModel", "provider=${best.first} steadyInferenceMs=${best.second / 1_000_000.0} input=224x224")
        }
        // A driver may disappear under resource pressure; session creation always has a CPU fallback.
        return runCatching { createSession(file, selectedBackend!!) }.getOrElse {
            selectedBackend = "CPU"
            mutableState.value = mutableState.value.copy(backend = "CPU")
            createSession(file, "CPU")
        }
    }

    @Synchronized
    fun activateCpuFallback(failedBackend: String): Boolean {
        if (failedBackend == "CPU") return false
        selectedBackend = "CPU"
        mutableState.value = mutableState.value.copy(backend = "CPU")
        return true
    }

    private fun probe(file: File, backend: String, control: ProcessingControl?): ProbeResult {
        createSession(file, backend).use { handle ->
            val values = FloatArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE) { index ->
                val x = index % MODEL_INPUT_SIZE
                val y = index / MODEL_INPUT_SIZE
                ((x * 7 + y * 13) and 255) / 255f
            }
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(values),
                longArrayOf(1, 1, MODEL_INPUT_SIZE.toLong(), MODEL_INPUT_SIZE.toLong()),
            ).use { tensor ->
                // Warm-up pays graph/provider initialization before timing steady inference.
                runProbe(handle, tensor, control).use { validateProbeOutput(it) }
                var best = Long.MAX_VALUE
                var samples = FloatArray(0)
                repeat(2) {
                    control?.check()
                    val start = System.nanoTime()
                    val result = runProbe(handle, tensor, control)
                    val elapsed = System.nanoTime() - start
                    result.use { samples = validateProbeOutput(it) }
                    best = min(best, elapsed)
                }
                return ProbeResult(best, samples)
            }
        }
    }

    private fun runProbe(
        handle: ModelSession,
        tensor: OnnxTensor,
        control: ProcessingControl?,
    ): OrtSession.Result {
        if (control == null) {
            return handle.session.run(mapOf(handle.session.inputNames.first() to tensor))
        }
        OrtSession.RunOptions().use { options ->
            control.bind(options)
            try {
                return handle.session.run(mapOf(handle.session.inputNames.first() to tensor), options)
            } finally {
                control.release(options)
            }
        }
    }

    private fun validateProbeOutput(result: OrtSession.Result): FloatArray {
        val output = result[0] as? OnnxTensor ?: throw PhotoFailure("model_shape")
        if (!output.info.shape.contentEquals(
                longArrayOf(1, 1, MODEL_OUTPUT_SIZE.toLong(), MODEL_OUTPUT_SIZE.toLong()),
            )
        ) throw PhotoFailure("model_shape")
        val buffer = output.floatBuffer
        val samples = FloatArray(64)
        val expectedCount = MODEL_OUTPUT_SIZE * MODEL_OUTPUT_SIZE
        val sampleStride = expectedCount / samples.size
        var minimum = Float.POSITIVE_INFINITY
        var maximum = Float.NEGATIVE_INFINITY
        var count = 0
        while (buffer.hasRemaining()) {
            val value = buffer.get()
            if (!value.isFinite()) throw PhotoFailure("inference")
            minimum = kotlin.math.min(minimum, value)
            maximum = kotlin.math.max(maximum, value)
            if (count % sampleStride == 0 && count / sampleStride < samples.size) {
                samples[count / sampleStride] = value
            }
            count++
        }
        if (count != expectedCount || maximum - minimum < 0.0001f) throw PhotoFailure("inference")
        return samples
    }

    private fun outputsAgree(reference: FloatArray, candidate: FloatArray): Boolean {
        if (reference.size != candidate.size || reference.isEmpty()) return false
        var totalDifference = 0.0
        var maximumDifference = 0f
        for (index in reference.indices) {
            val difference = kotlin.math.abs(reference[index] - candidate[index])
            totalDifference += difference
            maximumDifference = kotlin.math.max(maximumDifference, difference)
        }
        return maximumDifference <= 0.15f && totalDifference / reference.size <= 0.02
    }

    private fun createSession(file: File, backend: String): ModelSession {
        val options = OrtSession.SessionOptions()
        try {
            options.setInterOpNumThreads(1)
            options.setIntraOpNumThreads(if (backend == "XNNPACK") 1 else min(4, Runtime.getRuntime().availableProcessors()))
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.addConfigEntry("session.intra_op.allow_spinning", "0")
            when (backend) {
                "XNNPACK" -> options.addXnnpack(mapOf("intra_op_num_threads" to min(4, Runtime.getRuntime().availableProcessors()).toString()))
                "NNAPI" -> options.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
            }
            return ModelSession(environment.createSession(file.absolutePath, options), options, backend)
        } catch (failure: Throwable) { options.close(); throw failure }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class ProbeResult(val nanoseconds: Long, val samples: FloatArray)

    private companion object {
        const val MODEL_INPUT_SIZE = 224
        const val MODEL_OUTPUT_SIZE = MODEL_INPUT_SIZE * 3
    }
}

class ModelSession(
    val session: OrtSession,
    private val options: OrtSession.SessionOptions,
    val backend: String,
) : AutoCloseable {
    override fun close() { session.close(); options.close() }
}
