package com.roadvision.roadsense.pipeline

import com.roadvision.roadsense.camera.ConvertedFrame
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Single-worker scheduler whose queue can contain at most one pending frame. */
class FrameScheduler(
    private val onDropped: () -> Unit,
    private val processor: (ConvertedFrame) -> Unit,
) : Closeable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "roadvision-frame-processor")
    }
    private val pending = AtomicReference<ConvertedFrame?>(null)
    private val draining = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    fun submit(frame: ConvertedFrame) {
        if (closed.get()) {
            onDropped()
            return
        }
        if (pending.getAndSet(frame) != null) onDropped()
        scheduleDrain()
    }

    private fun scheduleDrain() {
        if (!draining.compareAndSet(false, true)) return
        executor.execute {
            try {
                while (!closed.get()) {
                    val frame = pending.getAndSet(null) ?: break
                    processor(frame)
                }
            } finally {
                draining.set(false)
                if (!closed.get() && pending.get() != null) scheduleDrain()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (pending.getAndSet(null) != null) onDropped()
        executor.shutdownNow()
    }
}
