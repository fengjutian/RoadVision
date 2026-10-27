package com.roadvision.roadsense.pipeline

import com.roadvision.roadsense.camera.ConvertedFrame
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FrameSchedulerTest {
    @Test
    fun retainsOnlyLatestPendingFrame() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val processed = mutableListOf<Long>()
        val dropped = AtomicInteger()
        val scheduler = FrameScheduler(onDropped = { dropped.incrementAndGet() }) { frame ->
            synchronized(processed) { processed += frame.frameId }
            if (frame.frameId == 1L) {
                firstStarted.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
            }
            finished.countDown()
        }
        try {
            scheduler.submit(frame(1))
            firstStarted.await(2, TimeUnit.SECONDS)
            scheduler.submit(frame(2))
            scheduler.submit(frame(3))
            releaseFirst.countDown()
            finished.await(2, TimeUnit.SECONDS)

            assertEquals(listOf(1L, 3L), synchronized(processed) { processed.toList() })
            assertEquals(1, dropped.get())
        } finally {
            scheduler.close()
        }
    }

    /**
     * The pipeline is closed right after the scheduler, and closing an ONNX session while its
     * run() is executing crashes natively. close() therefore has to wait for the worker.
     */
    @Test
    fun closeWaitsForTheFrameInFlight() {
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val scheduler = FrameScheduler(onDropped = {}) {
            started.countDown()
            Thread.sleep(250)
            finished.countDown()
        }
        try {
            scheduler.submit(frame(1))
            assertEquals(true, started.await(2, TimeUnit.SECONDS))
            scheduler.close()
            assertEquals("close() returned while the processor was still running", 0L, finished.count)
        } finally {
            scheduler.close()
        }
    }

    private fun frame(id: Long) = ConvertedFrame(id, id, 1, 1, 0, byteArrayOf(0, 0, 0))
}
