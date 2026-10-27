package com.roadvision.roadsense.inference

import android.content.Context
import com.roadvision.roadsense.pipeline.DetectionDecodeConfig
import com.roadvision.roadsense.pipeline.LaneDecodeConfig
import com.roadvision.roadsense.pipeline.LaneModelConfig
import com.roadvision.roadsense.pipeline.ModelAssetConfig
import com.roadvision.roadsense.pipeline.ModelBundleValidator
import com.roadvision.roadsense.pipeline.PipelineModelConfig
import com.roadvision.roadsense.pipeline.PipelineSchedule
import com.roadvision.roadsense.pipeline.PlateOcrModelConfig
import org.json.JSONArray
import org.json.JSONObject

object ModelBundleConfigLoader {
    const val DEFAULT_MANIFEST = "models/model-bundle.json"
    const val CLASSICAL_LANE_MODE = "classical"

    private val DEFAULT_PLATE_DECODE = DetectionDecodeConfig(
        confidenceThreshold = 0.5f,
        nmsIouThreshold = 0.4f,
        maxDetections = 50,
    )

    fun exists(context: Context, assetPath: String = DEFAULT_MANIFEST): Boolean = runCatching {
        context.assets.open(assetPath).close()
    }.isSuccess

    fun load(context: Context, assetPath: String = DEFAULT_MANIFEST): PipelineModelConfig {
        val root = context.assets.open(assetPath).bufferedReader().use { JSONObject(it.readText()) }
        val provider = ExecutionProvider.valueOf(root.optString("provider", "CPU").uppercase())
        val vehicle = root.getJSONObject("vehicle")
        val lane = root.optJSONObject("lane")?.let { json ->
            LaneModelConfig(
                asset = laneAsset(json),
                decode = LaneDecodeConfig(
                    laneDecodeThreshold(json.optJSONObject("decode")),
                ),
            )
        }
        val plateJson = root.optJSONObject("plate")
        val ocrJson = root.optJSONObject("ocr")
        require((plateJson == null) == (ocrJson == null)) {
            "plate and ocr must be configured together in $assetPath"
        }
        val plateOcr = if (plateJson != null && ocrJson != null) {
            PlateOcrModelConfig(
                plate = asset(plateJson),
                ocr = asset(ocrJson),
                alphabet = ocrJson.getJSONArray("alphabet").strings().map { it.single() },
                blankIndex = ocrJson.getInt("blankIndex"),
                plateDecode = detectionDecode(plateJson.optJSONObject("decode"), DEFAULT_PLATE_DECODE),
            )
        } else {
            null
        }
        val config = PipelineModelConfig(
            vehicle = asset(vehicle),
            vehicleLabels = stringMap(vehicle.getJSONObject("labels")),
            lane = lane,
            plateOcr = plateOcr,
            provider = provider,
            vehicleDecode = detectionDecode(vehicle.optJSONObject("decode"), DetectionDecodeConfig()),
            schedule = root.optJSONObject("schedule")?.let {
                PipelineSchedule(it.optInt("laneEveryFrames", 3), it.optInt("plateEveryFrames", 3))
            } ?: PipelineSchedule(),
        )
        ModelBundleValidator.validate(config)
        return config
    }

    private fun laneDecodeThreshold(json: JSONObject?): Float =
        json?.optDouble("threshold", 0.5)?.toFloat() ?: 0.5f

    /**
     * A lane section either names an ONNX asset or explicitly asks for the classical detector;
     * a lane block with neither is a typo, not a silent downgrade.
     */
    private fun laneAsset(json: JSONObject): ModelAssetConfig? {
        if (json.optString("assetPath").isNotEmpty()) return asset(json)
        require(json.optString("mode") == CLASSICAL_LANE_MODE) {
            "lane without an assetPath must declare \"mode\": \"$CLASSICAL_LANE_MODE\""
        }
        return null
    }

    private fun detectionDecode(json: JSONObject?, default: DetectionDecodeConfig): DetectionDecodeConfig {
        if (json == null) return default
        return DetectionDecodeConfig(
            confidenceThreshold = json.optDouble("confidence", default.confidenceThreshold.toDouble()).toFloat(),
            nmsIouThreshold = json.optDouble("nmsIou", default.nmsIouThreshold.toDouble()).toFloat(),
            maxDetections = json.optInt("maxDetections", default.maxDetections),
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
        padPixel = json.optInt("padPixel", 114),
    )

    private fun tensor(json: JSONObject) = TensorSpec(
        json.getString("name"), json.getJSONArray("shape").longArray(), TensorDataType.FLOAT32,
    )

    private fun stringMap(json: JSONObject): Map<Int, String> = buildMap {
        json.keys().forEach { key -> put(key.toInt(), json.getString(key)) }
    }

    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONArray.longArray() = LongArray(length()) { getLong(it) }
    private fun JSONArray.floatArray() = FloatArray(length()) { getDouble(it).toFloat() }
}
