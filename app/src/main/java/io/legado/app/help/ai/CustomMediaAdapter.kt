package io.legado.app.help.ai

import com.google.gson.JsonObject

/**
 * 自定义协议族适配器，用于接入尚未内置的厂商或自建中转。
 *
 * 请求体沿用 OpenAI 风格（自建中转最常见的形态）；响应字段优先读用户在设置里
 * 选定的路径（由「自动识别」生成，用户不需要手写 JSONPath），未配置时回落到
 * [AiMediaResponseParser] 的宽容解析。因此绝大多数中转其实什么都不配也能用。
 */
internal class CustomMediaAdapter : AiMediaProtocolAdapter {

    override fun buildSubmitRequest(setting: AiProviderSetting, spec: AiMediaGenerateSpec): AiMediaRequest {
        val protocol = AiMediaProtocol.CUSTOM
        val headers = AiMediaEndpoints.mediaHeaders(setting, protocol)
        val body = JsonObject().apply {
            addProperty("model", setting.modelFor(spec.kind))
            if (spec.kind == AiMediaKind.IMAGE) {
                val params = spec.image
                addProperty("prompt", params.prompt)
                if (params.n > 1) addProperty("n", params.n)
                params.size?.takeIf { it.isNotBlank() }?.let { addProperty("size", it) }
                params.aspectRatio?.takeIf { it.isNotBlank() }?.let { addProperty("aspect_ratio", it) }
                params.quality?.takeIf { it.isNotBlank() }?.let { addProperty("quality", it) }
                params.negativePrompt?.takeIf { it.isNotBlank() }?.let { addProperty("negative_prompt", it) }
                params.seed?.let { addProperty("seed", it) }
                spec.references.firstOrNull()?.let { payload ->
                    addProperty("image", AiMediaReferenceResolver.toDataUri(payload.bytes, payload.mimeType))
                }
            } else {
                val params = spec.video
                addProperty("prompt", params.prompt)
                params.aspectRatio?.takeIf { it.isNotBlank() }?.let { addProperty("aspect_ratio", it) }
                params.resolution?.takeIf { it.isNotBlank() }?.let { addProperty("size", it) }
                params.durationSeconds?.let {
                    addProperty("seconds", it)
                    addProperty("duration", it)
                }
                params.resolution?.takeIf { it.isNotBlank() }?.let { addProperty("resolution", it) }
                params.negativePrompt?.takeIf { it.isNotBlank() }?.let { addProperty("negative_prompt", it) }
                params.seed?.let { addProperty("seed", it) }
                spec.frames.firstFrame?.let { payload ->
                    addProperty("image", AiMediaReferenceResolver.toDataUri(payload.bytes, payload.mimeType))
                }
                spec.frames.lastFrame?.let { payload ->
                    addProperty("last_frame", AiMediaReferenceResolver.toDataUri(payload.bytes, payload.mimeType))
                }
            }
        }
        val url = if (spec.kind == AiMediaKind.IMAGE) {
            AiMediaEndpoints.imagePath(setting, protocol)
        } else {
            AiMediaEndpoints.videoPath(setting, protocol)
        }
        return AiMediaRequest(url = url, method = "POST", headers = headers, json = body)
    }

    override fun parseSubmit(json: JsonObject, spec: AiMediaGenerateSpec): AiMediaSubmit {
        val setting = spec.setting
        val configured = readResult(setting, json, spec.kind)
        if (!configured.isNullOrEmpty()) return AiMediaSubmit.Completed(configured)

        val detected = AiMediaResponseParser.collectResults(json, spec.kind)
        if (detected.isNotEmpty()) return AiMediaSubmit.Completed(detected)

        val taskId = setting.mediaTaskIdPath
            .takeIf { it.isNotBlank() }
            ?.let { AiMediaJsonPath.string(json, it) }
            ?: AiMediaResponseParser.findTaskId(json)
            ?: error("未能识别任务 ID。请在设置中粘贴一次接口返回并点击「自动识别」选择任务 ID 字段。")
        return AiMediaSubmit.Async(taskId, json.toString().take(1000))
    }

    override fun buildQueryRequest(
        setting: AiProviderSetting,
        taskId: String,
        spec: AiMediaGenerateSpec
    ): AiMediaRequest {
        val protocol = AiMediaProtocol.CUSTOM
        return AiMediaRequest(
            url = AiMediaEndpoints.taskUrl(setting, protocol, taskId, spec.kind),
            method = "GET",
            headers = AiMediaEndpoints.mediaHeaders(setting, protocol)
        )
    }

    override fun parseQuery(json: JsonObject, spec: AiMediaGenerateSpec): AiMediaPoll {
        val setting = spec.setting
        val state = setting.mediaStatusPath
            .takeIf { it.isNotBlank() }
            ?.let { AiMediaTaskState.from(AiMediaJsonPath.string(json, it)) }
            ?.takeIf { it != AiMediaTaskState.UNKNOWN }
            ?: AiMediaResponseParser.findState(json)

        val error = setting.mediaErrorPath
            .takeIf { it.isNotBlank() }
            ?.let { AiMediaJsonPath.string(json, it) }
            ?: AiMediaResponseParser.findError(json)

        if (state == AiMediaTaskState.SUCCEEDED) {
            val results = readResult(setting, json, spec.kind)
                ?.ifEmpty { null }
                ?: AiMediaResponseParser.collectResults(json, spec.kind)
            if (results.isEmpty() && error == null) {
                return AiMediaPoll(
                    state = AiMediaTaskState.RUNNING,
                    message = "任务已完成，正在获取结果"
                )
            }
            return AiMediaPoll(state = state, percent = 100, results = results)
        }
        return AiMediaPoll(state = state, message = error)
    }

    private fun readResult(
        setting: AiProviderSetting,
        json: JsonObject,
        kind: AiMediaKind
    ): List<AiMediaResult>? {
        val path = setting.mediaResultPath.takeIf { it.isNotBlank() } ?: return null
        val value = AiMediaJsonPath.string(json, path)?.takeIf { it.isNotBlank() } ?: return null
        val result = if (value.startsWith("http", ignoreCase = true)) {
            AiMediaResult(kind = kind, url = value, mimeType = AiMediaResponseParser.mimeFor(kind))
        } else {
            AiMediaResult(kind = kind, base64 = value, mimeType = AiMediaResponseParser.mimeFor(kind))
        }
        return listOf(result)
    }
}
