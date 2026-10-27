package com.roadvision.roadsense.pipeline

import java.util.concurrent.ConcurrentHashMap

class OcrScheduler(
    private val minimumIntervalNs: Long = 400_000_000L,
    private val maximumAttemptsPerTrack: Int = 6,
) {
    private data class State(var lastAttemptNs: Long, var attempts: Int)
    private val states = ConcurrentHashMap<Long, State>()

    fun shouldRun(trackId: Long, timestampNs: Long, cropAcceptable: Boolean): Boolean {
        if (!cropAcceptable) return false
        val state = states[trackId]
        if (state != null && (state.attempts >= maximumAttemptsPerTrack || timestampNs - state.lastAttemptNs < minimumIntervalNs)) {
            return false
        }
        states.compute(trackId) { _, old -> State(timestampNs, (old?.attempts ?: 0) + 1) }
        return true
    }

    fun forget(trackId: Long) { states.remove(trackId) }

    /** Drops per-track counters once a track leaves the tracker, keeping long runs bounded. */
    fun prune(activeTrackIds: Set<Long>) {
        states.keys.retainAll(activeTrackIds)
    }

    internal fun trackedTrackCount(): Int = states.size
}
