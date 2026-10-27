package com.roadvision.roadsense.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.roadvision.roadsense.diagnostics.PerformanceRecorder
import com.roadvision.roadsense.pipeline.FrameScheduler
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

sealed interface CameraStatus {
    data object Starting : CameraStatus
    data object Running : CameraStatus
    data object Stopped : CameraStatus
    data class Error(val message: String) : CameraStatus
}

class CameraController(
    private val recorder: PerformanceRecorder,
    private val scheduler: FrameScheduler,
    private val onStatus: (CameraStatus) -> Unit,
) : Closeable {
    private val analyzerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "roadvision-camera-analyzer")
    }
    private val frameIds = AtomicLong()
    private var provider: ProcessCameraProvider? = null
    private var generation = 0L

    fun start(context: Context, owner: LifecycleOwner, previewView: PreviewView) {
        val requestGeneration = ++generation
        onStatus(CameraStatus.Starting)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (requestGeneration != generation) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(analyzerExecutor) { image ->
                    recorder.onCaptured()
                    try {
                        scheduler.submit(FrameConverter.convert(image, frameIds.incrementAndGet()))
                    } catch (error: Throwable) {
                        onStatus(CameraStatus.Error(error.message ?: "帧转换失败"))
                    } finally {
                        // Never hand an ImageProxy to another thread.
                        image.close()
                    }
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                onStatus(CameraStatus.Running)
            } catch (error: Throwable) {
                onStatus(CameraStatus.Error(error.message ?: "相机启动失败"))
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        generation++
        provider?.unbindAll()
        provider = null
        onStatus(CameraStatus.Stopped)
    }

    override fun close() {
        stop()
        analyzerExecutor.shutdownNow()
    }
}
