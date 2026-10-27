package com.roadvision.roadsense.diagnostics

import com.roadvision.roadsense.data.DetectionEventEntity

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val device: String,
    val sdkInt: Int,
    val androidRelease: String,
    val buildDisplay: String,
    val socModel: String?,
)

data class ModelDescriptor(
    val role: String,
    val id: String,
    val version: String,
    val sha256: String,
)

data class ModelRuntimeInfo(
    val requestedProvider: String,
    val actualProvider: String,
    val fallbackToCpu: Boolean,
    val models: List<ModelDescriptor>,
    val vehicleConfidence: Float,
    val plateConfidence: Float,
    val laneThreshold: Float,
)

data class ReportSnapshot(
    val generatedAtEpochMs: Long,
    val appVersionName: String,
    val appVersionCode: Long,
    val device: DeviceInfo,
    val diagnostics: DiagnosticsState,
    val providerRequested: String,
    val providerActual: String,
    val fallbackToCpu: Boolean,
    val retentionDays: Int,
    val models: List<ModelDescriptor>,
    val events: List<DetectionEventEntity>,
)

/**
 * Builds a deterministic JSON report without third-party serializers so it can be unit-tested
 * on the JVM. The report is only produced after an explicit user action.
 */
object DiagnosticsReportBuilder {
    fun build(snapshot: ReportSnapshot): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"generatedAtEpochMs\": ${snapshot.generatedAtEpochMs},\n")
        append("  \"app\": {\n")
        append("    \"versionName\": ${quote(snapshot.appVersionName)},\n")
        append("    \"versionCode\": ${snapshot.appVersionCode}\n")
        append("  },\n")
        append("  \"device\": {\n")
        append("    \"manufacturer\": ${quote(snapshot.device.manufacturer)},\n")
        append("    \"model\": ${quote(snapshot.device.model)},\n")
        append("    \"device\": ${quote(snapshot.device.device)},\n")
        append("    \"sdkInt\": ${snapshot.device.sdkInt},\n")
        append("    \"androidRelease\": ${quote(snapshot.device.androidRelease)},\n")
        append("    \"buildDisplay\": ${quote(snapshot.device.buildDisplay)},\n")
        append("    \"socModel\": ${snapshot.device.socModel?.let(::quote) ?: "null"}\n")
        append("  },\n")
        append("  \"provider\": {\n")
        append("    \"requested\": ${quote(snapshot.providerRequested)},\n")
        append("    \"actual\": ${quote(snapshot.providerActual)},\n")
        append("    \"fallbackToCpu\": ${snapshot.fallbackToCpu}\n")
        append("  },\n")
        append("  \"retentionDays\": ${snapshot.retentionDays},\n")
        appendDiagnostics(snapshot.diagnostics)
        append(",\n")
        appendModels(snapshot.models)
        append(",\n")
        appendEvents(snapshot.events)
        append("\n}\n")
    }

    private fun StringBuilder.appendDiagnostics(state: DiagnosticsState) {
        append("  \"diagnostics\": {\n")
        append("    \"cameraFps\": ${state.cameraFps},\n")
        append("    \"processedFps\": ${state.processedFps},\n")
        append("    \"capturedFrames\": ${state.capturedFrames},\n")
        append("    \"processedFrames\": ${state.processedFrames},\n")
        append("    \"droppedFrames\": ${state.droppedFrames},\n")
        append("    \"errors\": ${state.errors},\n")
        append("    \"lastLatencyMs\": ${state.lastLatencyMs ?: "null"},\n")
        append("    \"latencyP50Ms\": ${state.latencyP50Ms ?: "null"},\n")
        append("    \"latencyP95Ms\": ${state.latencyP95Ms ?: "null"},\n")
        append("    \"inferenceP50Ms\": ${state.inferenceP50Ms ?: "null"},\n")
        append("    \"inferenceP95Ms\": ${state.inferenceP95Ms ?: "null"},\n")
        append("    \"memoryMb\": ${state.memoryMb},\n")
        append("    \"thermalStatus\": ${state.thermalStatus ?: "null"},\n")
        append("    \"executionProvider\": ${quote(state.executionProvider)}\n")
        append("  }")
    }

    private fun StringBuilder.appendModels(models: List<ModelDescriptor>) {
        append("  \"models\": [")
        models.forEachIndexed { index, model ->
            if (index > 0) append(",")
            append("\n    {")
            append("\"role\": ${quote(model.role)}, ")
            append("\"id\": ${quote(model.id)}, ")
            append("\"version\": ${quote(model.version)}, ")
            append("\"sha256\": ${quote(model.sha256)}")
            append("}")
        }
        if (models.isNotEmpty()) append("\n  ")
        append("]")
    }

    private fun StringBuilder.appendEvents(events: List<DetectionEventEntity>) {
        append("  \"events\": [")
        events.forEachIndexed { index, event ->
            if (index > 0) append(",")
            append("\n    {")
            append("\"id\": ${event.id}, ")
            append("\"trackId\": ${event.trackId}, ")
            append("\"plateText\": ${event.plateText?.let(::quote) ?: "null"}, ")
            append("\"confidence\": ${event.confidence}, ")
            append("\"uncertain\": ${event.uncertain}, ")
            append("\"observedAtEpochMs\": ${event.observedAtEpochMs}")
            append("}")
        }
        if (events.isNotEmpty()) append("\n  ")
        append("]")
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}
