package com.roadvision.roadsense

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import com.roadvision.roadsense.camera.ConvertedFrame
import com.roadvision.roadsense.camera.FrameConverter
import com.roadvision.roadsense.diagnostics.PerformanceRecorder
import com.roadvision.roadsense.inference.ModelBundleConfigLoader
import com.roadvision.roadsense.pipeline.PipelineFactory
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Debug-only synthetic pipeline benchmark.
 *
 * It deliberately does not touch CameraX: frames are generated in memory and fed straight into the
 * production pipeline (letterbox preprocessing + ONNX Runtime + tracker). This separates model and
 * preprocessing cost from camera capture/scene/thermal variation, which made earlier FPS numbers
 * non-comparable.
 *
 * Launch:
 *   adb shell am start -n com.roadvision.roadsense/.BenchmarkActivity
 * Read:
 *   adb shell run-as com.roadvision.roadsense cat files/benchmark.json
 */
class BenchmarkActivity : Activity() {
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.TOP or Gravity.START
            setPadding(24, 48, 24, 24)
            text = "Running synthetic pipeline benchmark…"
        }
        setContentView(output)
        Thread {
            val message = runCatching { runBenchmark() }.getOrElse { error ->
                "FAILED: ${error.javaClass.simpleName}: ${error.message}"
            }
            Log.i(TAG, message)
            runOnUiThread { output.text = message }
        }.start()
    }

    private fun runBenchmark(): String {
        val config = ModelBundleConfigLoader.load(applicationContext)
        val recorder = PerformanceRecorder()
        val pipeline = PipelineFactory.create(applicationContext, config, recorder, {}, {})
        val sourceWidth = 480
        val sourceHeight = 640
        val rotation = 90
        val yBuffer = ByteBuffer.wrap(ByteArray(sourceWidth * sourceHeight) { ((it * 31 + 7) and 0xff).toByte() })
        val chromaWidth = sourceWidth / 2
        val uBuffer = ByteBuffer.wrap(ByteArray(chromaWidth * (sourceHeight / 2)) { 128.toByte() })
        val vBuffer = ByteBuffer.wrap(ByteArray(chromaWidth * (sourceHeight / 2)) { 128.toByte() })

        fun convertedFrame(index: Int): ConvertedFrame {
            val rgb = FrameConverter.yuvToRgbRotated(
                yBuffer = yBuffer,
                yRowStride = sourceWidth,
                yPixelStride = 1,
                uBuffer = uBuffer,
                uRowStride = chromaWidth,
                uPixelStride = 1,
                vBuffer = vBuffer,
                vRowStride = chromaWidth,
                vPixelStride = 1,
                width = sourceWidth,
                height = sourceHeight,
                rotation = rotation,
            )
            return ConvertedFrame(
                frameId = index.toLong(),
                timestampNs = (index + 1) * 33_000_000L,
                width = sourceHeight,
                height = sourceWidth,
                rotationDegrees = rotation,
                rgb = rgb,
            )
        }

        val runs = 30
        try {
            repeat(3) { pipeline.process(convertedFrame(it)) }
            val e2e = ArrayList<Long>(runs)
            val convert = ArrayList<Long>(runs)
            repeat(runs) { index ->
                val frameStarted = System.nanoTime()
                val frame = convertedFrame(index + 3)
                val converted = System.nanoTime()
                pipeline.process(frame)
                val finished = System.nanoTime()
                convert += converted - frameStarted
                e2e += finished - frameStarted
            }
            fun percentile(samples: List<Long>, quantile: Double): Double {
                val sorted = samples.sorted()
                return sorted[((sorted.size - 1) * quantile).toInt().coerceIn(sorted.indices)] / 1_000_000.0
            }
            val totalSeconds = e2e.sum() / 1_000_000_000.0
            val state = recorder.state.value
            val report = """
                input=${config.vehicle.contract.input.shape.joinToString("x")}
                models=${pipeline.enabledModels.joinToString("+")}
                frames=$runs elapsed=${"%.2f".format(Locale.US, totalSeconds)}s
                e2eFps=${"%.1f".format(Locale.US, runs / totalSeconds)}
                e2eP50=${"%.1f".format(Locale.US, percentile(e2e, 0.50))}ms e2eP95=${"%.1f".format(Locale.US, percentile(e2e, 0.95))}ms
                convertP50=${"%.1f".format(Locale.US, percentile(convert, 0.50))}ms convertP95=${"%.1f".format(Locale.US, percentile(convert, 0.95))}ms
                inferenceP50=${"%.1f".format(Locale.US, state.inferenceP50Ms ?: 0.0)}ms inferenceP95=${"%.1f".format(Locale.US, state.inferenceP95Ms ?: 0.0)}ms
                provider=${state.executionProvider}
            """.trimIndent()
            openFileOutput("benchmark.json", MODE_PRIVATE).use { it.write(report.toByteArray()) }
            return report
        } finally {
            pipeline.close()
        }
    }

    private companion object {
        const val TAG = "RoadVisionBench"
    }
}
