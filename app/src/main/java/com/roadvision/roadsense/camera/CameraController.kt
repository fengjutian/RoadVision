package com.roadvision.roadsense.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
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

/**
 * Owns the CameraX use cases. The owner is the capture foreground service, so analysis keeps
 * running when the Activity is stopped or the screen turns off; the Activity only attaches a
 * preview surface while it is visible.
 */
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
    private var preview: Preview? = null
    private var lastContext: Context? = null
    private var lastOwner: LifecycleOwner? = null
    @Volatile private var surfaceProvider: Preview.SurfaceProvider? = null
    private var generation = 0L

    /**
     * Called by the Activity while a preview surface exists. If the service is already analysing
     * without a preview (for example when capture started before the UI attached), rebind to add it.
     */
    fun setSurfaceProvider(newProvider: Preview.SurfaceProvider?) {
        val needsPreviewBinding = surfaceProvider == null && newProvider != null && preview == null && provider != null
        surfaceProvider = newProvider
        preview?.setSurfaceProvider(newProvider)
        if (needsPreviewBinding) {
            val context = lastContext ?: return
            val owner = lastOwner ?: return
            start(context, owner)
        }
    }

    fun start(context: Context, owner: LifecycleOwner) {
        val requestGeneration = ++generation
        lastContext = context
        lastOwner = owner
        onStatus(CameraStatus.Starting)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (requestGeneration != generation) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val currentPreview = surfaceProvider?.let { surface ->
                    Preview.Builder().build().also { it.setSurfaceProvider(surface) }
                }
                preview = currentPreview
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(analyzerExecutor) { image ->
                    recorder.onCaptured()
                    try {
                        scheduler.submit(FrameConverter.convert(image, frameIds.incrementAndGet()))
                    } catch (error: Throwable) {
                        recorder.onError()
                        onStatus(CameraStatus.Error(error.message ?: "帧转换失败"))
                    } finally {
                        // Never hand an ImageProxy to another thread.
                        image.close()
                    }
                }
                val useCases: Array<UseCase> = currentPreview?.let { arrayOf(it, analysis) } ?: arrayOf(analysis)
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases)
                onStatus(CameraStatus.Running)
            } catch (error: Throwable) {
                recorder.onError()
                onStatus(CameraStatus.Error(error.message ?: "相机启动失败"))
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        generation++
        provider?.unbindAll()
        provider = null
        preview = null
        onStatus(CameraStatus.Stopped)
    }

    override fun close() {
        stop()
        lastContext = null
        lastOwner = null
        analyzerExecutor.shutdownNow()
    }
}
