package com.roadvision.roadsense.ui

private val ALL_MODEL_ROLES = listOf("vehicle", "lane", "plate", "ocr")
private val OPTIONAL_MODEL_ROLES = listOf("lane", "plate", "ocr")

fun modelRoleLabel(role: String): String = when (role) {
    "vehicle" -> "车辆检测"
    "lane" -> "车道识别"
    "plate" -> "车牌检测"
    "ocr" -> "车牌 OCR"
    else -> role
}

fun enabledModelsLabel(enabled: Collection<String>): String =
    ALL_MODEL_ROLES.filter { it in enabled }.joinToString(" + ") { modelRoleLabel(it) }

fun missingOptionalModelRoles(enabled: Collection<String>): List<String> =
    OPTIONAL_MODEL_ROLES.filterNot { it in enabled }

fun modelStatusText(state: ModelRuntimeState): String = when (state) {
    ModelRuntimeState.NotConfigured -> "模型未配置：等待可用权重与 models/model-bundle.json（至少需要车辆模型）"
    ModelRuntimeState.Loading -> "正在校验并加载模型…"
    is ModelRuntimeState.Running -> buildString {
        append("模型运行中：${state.provider}")
        if (state.fallbackToCpu) append("（已回退 CPU）")
        if (missingOptionalModelRoles(state.enabledModels).isNotEmpty()) {
            append(" · 降级：仅 ${enabledModelsLabel(state.enabledModels)}")
        }
    }
    is ModelRuntimeState.Failed -> "模型不可用：${state.message}"
}
