package com.roadvision.roadsense.inference

import android.content.Context
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.TensorInfo
import java.io.Closeable
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap

enum class ExecutionProvider { CPU, XNNPACK, NNAPI }

data class SessionKey(val assetPath: String, val provider: ExecutionProvider)
data class TensorOutput(val name: String, val shape: LongArray, val values: FloatArray)

class OrtRuntimeManager(private val context: Context) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val sessions = ConcurrentHashMap<SessionKey, OrtSession>()

    fun session(assetPath: String, provider: ExecutionProvider): OrtSession =
        sessions.computeIfAbsent(SessionKey(assetPath, provider)) {
            val options = OrtSession.SessionOptions()
            configureProvider(options, provider)
            try {
                context.assets.open(assetPath).use { input ->
                    environment.createSession(input.readBytes(), options)
                }
            } finally {
                options.close()
            }
        }

    fun run(
        assetPath: String,
        provider: ExecutionProvider,
        inputName: String,
        inputShape: LongArray,
        input: FloatArray,
        outputNames: Set<String>,
    ): Map<String, TensorOutput> {
        require(inputShape.fold(1L, Long::times) == input.size.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), inputShape).use { tensor ->
            session(assetPath, provider).run(mapOf(inputName to tensor), outputNames).use { result ->
                return outputNames.associateWith { name ->
                    val value = result.get(name).orElseThrow { IllegalArgumentException("Missing model output: $name") }
                    val info = value.info as? TensorInfo ?: error("$name is not a tensor")
                    val flattened = ArrayList<Float>()
                    flattenFloats(value.value, flattened)
                    TensorOutput(name, info.shape, flattened.toFloatArray()).also {
                        OutputValidator.validateFinite(it.values, name)
                    }
                }
            }
        }
    }

    private fun flattenFloats(value: Any?, output: MutableList<Float>) {
        when (value) {
            is Float -> output += value
            is FloatArray -> value.forEach(output::add)
            is Array<*> -> value.forEach { flattenFloats(it, output) }
            else -> error("Only FLOAT32 tensor outputs are supported, got ${value?.javaClass?.name}")
        }
    }

    private fun configureProvider(options: OrtSession.SessionOptions, provider: ExecutionProvider) {
        when (provider) {
            ExecutionProvider.CPU -> Unit
            ExecutionProvider.XNNPACK -> invokeProvider(options, "addXnnpack", mapOf("intra_op_num_threads" to "2"))
            ExecutionProvider.NNAPI -> invokeProvider(options, "addNnapi")
        }
    }

    private fun invokeProvider(options: OrtSession.SessionOptions, methodName: String, argument: Any? = null) {
        val method = options.javaClass.methods.firstOrNull {
            it.name.equals(methodName, ignoreCase = true) && it.parameterCount == if (argument == null) 0 else 1
        } ?: error("ONNX Runtime does not expose provider $methodName on this build")
        if (argument == null) method.invoke(options) else method.invoke(options, argument)
    }

    override fun close() {
        sessions.values.forEach(OrtSession::close)
        sessions.clear()
        environment.close()
    }
}
