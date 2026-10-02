package dev.localphoto.enhancer.ai

import android.content.Context
import android.util.Log
import ai.onnxruntime.*
import ai.onnxruntime.providers.NNAPIFlags
import dev.localphoto.enhancer.data.PhotoFailure
import dev.localphoto.enhancer.BuildConfig
import dev.localphoto.enhancer.processing.ProcessingControl
import dev.localphoto.enhancer.processing.ProcessingStopped
import dev.localphoto.core.NativeModelGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

data class ModelStatus(val id: String, val label: String, val bytes: Long,
    val ready: Boolean = false, val preparing: Boolean = false, val error: String? = null)

class ModelManager(private val context: Context) {
    val environment: OrtEnvironment = OrtEnvironment.getEnvironment().also { environment ->
        // The application is offline and emits no runtime analytics. Some vendor builds may
        // not expose this native switch, so inability to disable an already absent channel is safe.
        runCatching { environment.setTelemetry(false) }
    }
    private val mutableState = MutableStateFlow(ModelState())
    val state = mutableState.asStateFlow()
    private val manifest by lazy {
        JSONObject(context.assets.open("models/manifest.json").bufferedReader().use { it.readText() })
    }
    private val entries by lazy {
        manifest.getJSONArray("models").let { models ->
            (0 until models.length()).associate { index ->
                val model = models.getJSONObject(index)
                model.getString("id") to model
            }
        }
    }
    private val mutableCatalog = MutableStateFlow(entries.mapValues { (id, model) ->
        ModelStatus(id, model.optString("label", id), model.getLong("bytes"))
    })
    val catalog = mutableCatalog.asStateFlow()
    private val nativeGate = NativeModelGate()
    private var selectedBackend: String? = null

    @Synchronized
    fun prepare(): File = prepareModel("espcn-x3")

    /** Assemble bundled parts only; a complete digest is checked on every open. */
    @Synchronized
    fun prepareModel(id: String, control: ProcessingControl? = null): File {
        val model = entries[id] ?: throw PhotoFailure("model_init")
        val bytes = model.getLong("bytes")
        val expected = model.getString("sha256").lowercase()
        if (id == "espcn-x3") mutableState.value = mutableState.value.copy(requiredBytes = bytes)
        control?.check()
        val directory = File(context.filesDir, "models")
        if (!directory.isDirectory && !directory.mkdirs()) throw PhotoFailure("storage")
        val filename = model.getString("file")
        if (!filename.matches(Regex("[a-zA-Z0-9._-]+"))) throw PhotoFailure("model_integrity")
        val destination = File(directory, filename)
        if (!destination.isFile || destination.length() != bytes || sha256(destination, control) != expected) {
            val capabilities = DeviceCapabilityDetector.detect(context)
            if (capabilities.storageBytes < bytes * 2 + 32L * 1024 * 1024) throw PhotoFailure("storage")
            changeStatus(id) { it.copy(preparing = true, error = null) }
            val temporary = File(directory, destination.name + ".part")
            try {
                temporary.outputStream().use { output ->
                    val parts = model.optJSONArray("parts")
                    if (parts == null) {
                        copyAsset("models/$filename", output, bytes, expected, control)
                    } else {
                        for (index in 0 until parts.length()) {
                            val part = parts.getJSONObject(index)
                            val partFile = part.getString("file")
                            if (!partFile.matches(Regex("[a-zA-Z0-9._-]+"))) throw PhotoFailure("model_integrity")
                            copyAsset("models/$partFile", output, part.getLong("bytes"), part.getString("sha256"), control)
                        }
                    }
                }
                if (temporary.length() != bytes || sha256(temporary, control) != expected) throw PhotoFailure("model_integrity")
                control?.check()
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
            } catch (failure: Throwable) {
                if (failure !is ProcessingStopped) changeStatus(id) { it.copy(ready = false, error = (failure as? PhotoFailure)?.code ?: "model_init") }
                throw failure
            } finally {
                temporary.delete()
                changeStatus(id) { it.copy(preparing = false) }
            }
        }
        if (id == "espcn-x3") mutableState.value = mutableState.value.copy(ready = true, error = null)
        return destination
    }

    private fun copyAsset(path: String, output: java.io.OutputStream, bytes: Long,
        expected: String, control: ProcessingControl?) {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        context.assets.open(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                control?.check()
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                if (count > bytes) throw PhotoFailure("model_integrity")
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
        if (count != bytes || digest.digest().hex() != expected.lowercase()) throw PhotoFailure("model_integrity")
    }

    fun openModuleSession(id: String, control: ProcessingControl? = null): ModelSession {
        val lease = nativeGate.acquire { control?.check() }
        changeStatus(id) { it.copy(preparing = true, error = null) }
        try {
            val file = prepareModel(id, control)
            control?.check()
            requireHeadroom(id)
            return createSession(file, "CPU", id, lease)
        } catch (failure: Throwable) {
            lease.close()
            control?.check()
            changeStatus(id) { it.copy(ready = false, error = (failure as? PhotoFailure)?.code ?: "model_init") }
            throw if (failure is PhotoFailure) failure else PhotoFailure("model_init", failure)
        } finally { changeStatus(id) { it.copy(preparing = false) } }
    }

    @Synchronized
    fun markInferenceReady(id: String) { changeStatus(id) { it.copy(ready = true, error = null) } }

    @Synchronized
    fun markInferenceFailed(id: String, code: String) { changeStatus(id) { it.copy(ready = false, error = code) } }

    fun requireHeadroom(id: String) {
        val estimate = entries[id]?.optLong("requiredAvailableMemoryBytes", 0) ?: throw PhotoFailure("model_init")
        if (estimate > 0 && DeviceCapabilityDetector.detect(context).availableMemory < estimate) {
            throw PhotoFailure("module_memory")
        }
    }

    /** Sessions are probed and released serially, keeping failures independent. */
    fun initializeAll() {
        for (id in entries.keys) {
            try {
                if (id == "espcn-x3") {
                    openSession().use { }
                    markInferenceReady(id)
                } else initializeModule(id)
            } catch (failure: Exception) {
                markInferenceFailed(id, (failure as? PhotoFailure)?.code ?: "model_init")
            }
        }
    }

    private fun initializeModule(id: String) {
        openModuleSession(id).use { handle ->
            val inputs = entries.getValue(id).getJSONArray("probeInputs")
            val tensors = linkedMapOf<String, OnnxTensor>()
            try {
                for (index in 0 until inputs.length()) {
                    val input = inputs.getJSONObject(index)
                    val shapeJson = input.getJSONArray("shape")
                    val shape = LongArray(shapeJson.length()) { shapeJson.getLong(it) }
                    val count = shape.fold(1L, Math::multiplyExact).toInt()
                    val maximum = input.optDouble("range", 1.0).toFloat()
                    val values = FloatArray(count) { n ->
                        if (input.getString("name") == "mask") 0f else ((n * 7 + n / 512) and 255) / 255f * maximum
                    }
                    tensors[input.getString("name")] = OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), shape)
                }
                handle.session.run(tensors).use { result ->
                    for (index in 0 until result.size()) {
                        val tensor = result[index] as? OnnxTensor ?: throw PhotoFailure("model_shape")
                        val buffer = tensor.floatBuffer
                        if (!buffer.hasRemaining()) throw PhotoFailure("model_shape")
                        while (buffer.hasRemaining()) if (!buffer.get().isFinite()) throw PhotoFailure("inference")
                    }
                }
                markInferenceReady(id)
            } finally { tensors.values.forEach { it.close() } }
        }
    }

    private fun changeStatus(id: String, update: (ModelStatus) -> ModelStatus) {
        mutableCatalog.update { current -> current[id]?.let { current + (id to update(it)) } ?: current }
    }

    /** Run the actual bundled graph with each available provider; cache the fastest usable path. */
    fun openSession(control: ProcessingControl? = null): ModelSession {
        val lease = nativeGate.acquire { control?.check() }
        try {
        val file = prepareModel("espcn-x3", control)
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
            markInferenceReady("espcn-x3")
            mutableState.value = mutableState.value.copy(
                backend = best.first,
                probeMilliseconds = best.second / 1_000_000.0,
            )
            if (BuildConfig.DEBUG) Log.d("LocalPhotoModel", "provider=${best.first} steadyInferenceMs=${best.second / 1_000_000.0} input=224x224")
        }
        // A driver may disappear under resource pressure; session creation always has a CPU fallback.
        return runCatching { createSession(file, selectedBackend!!, lease = lease) }.getOrElse {
            selectedBackend = "CPU"
            mutableState.value = mutableState.value.copy(backend = "CPU")
            createSession(file, "CPU", lease = lease)
        }
        } catch (failure: Throwable) { lease.close(); throw failure }
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

    private fun createSession(file: File, backend: String, id: String? = null, lease: AutoCloseable? = null): ModelSession {
        val options = OrtSession.SessionOptions()
        try {
            options.setInterOpNumThreads(1)
            options.setIntraOpNumThreads(if (backend == "XNNPACK") 1 else min(if (id == null) 4 else 2, Runtime.getRuntime().availableProcessors()))
            if (id == "color-ddcolor") {
                // Measured CPU peak falls from 3.2 GB to ~1.08 GB without changing weights/resolution.
                options.setCPUArenaAllocator(false)
                options.setMemoryPatternOptimization(false)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            } else options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.addConfigEntry("session.intra_op.allow_spinning", "0")
            when (backend) {
                "XNNPACK" -> options.addXnnpack(mapOf("intra_op_num_threads" to min(4, Runtime.getRuntime().availableProcessors()).toString()))
                "NNAPI" -> options.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
            }
            return ModelSession(environment.createSession(file.absolutePath, options), options, backend, lease)
        } catch (failure: Throwable) { options.close(); throw failure }
    }

    private fun sha256(file: File, control: ProcessingControl? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { control?.check(); val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

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
    private val lease: AutoCloseable? = null,
) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { session.close() }
            finally { try { options.close() } finally { lease?.close() } }
        }
    }
}
