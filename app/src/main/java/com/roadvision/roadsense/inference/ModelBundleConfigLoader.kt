package com.roadvision.roadsense.inference

import android.content.Context
import com.roadvision.roadsense.pipeline.ModelAssetConfig
import com.roadvision.roadsense.pipeline.PipelineModelConfig
import com.roadvision.roadsense.pipeline.PipelineSchedule
import org.json.JSONArray
import org.json.JSONObject

object ModelBundleConfigLoader {
    const val DEFAULT_MANIFEST = "models/model-bundle.json"

    fun exists(context: Context, assetPath: String = DEFAULT_MANIFEST): Boolean = runCatching {
        context.assets.open(assetPath).close()
    }.isSuccess

    fun load(context: Context, assetPath: String = DEFAULT_MANIFEST): PipelineModelConfig {
        val root = context.assets.open(assetPath).bufferedReader().use { JSONObject(it.readText()) }
        val provider = ExecutionProvider.valueOf(root.optString("provider", "CPU").uppercase())
        val vehicle = root.getJSONObject("vehicle")
        val lane = root.getJSONObject("lane")
        val plate = root.getJSONObject("plate")
        val ocr = root.getJSONObject("ocr")
        return PipelineModelConfig(
            vehicle = asset(vehicle),
            vehicleLabels = stringMap(vehicle.getJSONObject("labels")),
            lane = asset(lane),
            plate = asset(plate),
            ocr = asset(ocr),
            ocrAlphabet = ocr.getJSONArray("alphabet").strings().map { it.single() },
            ocrBlankIndex = ocr.getInt("blankIndex"),
            provider = provider,
            schedule = root.optJSONObject("schedule")?.let {
                PipelineSchedule(it.optInt("laneEveryFrames", 3), it.optInt("plateEveryFrames", 3))
            } ?: PipelineSchedule(),
        )
    }

    private fun asset(json: JSONObject) = ModelAssetConfig(
        assetPath = json.getString("assetPath"),
        contract = contract(json.getJSONObject("contract")),
    )

    private fun contract(json: JSONObject): ModelContract = ModelContract(
        id = json.getString("id"),
        version = json.getString("version"),
        sha256 = json.getString("sha256"),
        input = tensor(json.getJSONObject("input")),
        outputs = json.getJSONArray("outputs").objects().map(::tensor),
        layout = TensorLayout.valueOf(json.getString("layout").uppercase()),
        colorOrder = ColorOrder.valueOf(json.getString("colorOrder").uppercase()),
        scale = json.optDouble("scale", 1.0 / 255.0).toFloat(),
        mean = json.optJSONArray("mean")?.floatArray() ?: floatArrayOf(0f, 0f, 0f),
        std = json.optJSONArray("std")?.floatArray() ?: floatArrayOf(1f, 1f, 1f),
    )

    private fun tensor(json: JSONObject) = TensorSpec(
        json.getString("name"), json.getJSONArray("shape").longArray(), TensorDataType.FLOAT32,
    )

    private fun stringMap(json: JSONObject): Map<Int, String> = buildMap {
        json.keys().forEach { key -> put(key.toInt(), json.getString(key)) }
    }

    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private fun JSONArray.strings() = (0 until length()).map(::getString)
    private fun JSONArray.longArray() = LongArray(length()) { getLong(it) }
    private fun JSONArray.floatArray() = FloatArray(length()) { getDouble(it).toFloat() }
}
