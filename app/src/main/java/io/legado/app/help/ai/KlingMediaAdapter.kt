package io.legado.app.help.ai

import com.google.gson.JsonObject

/**
 * 可灵（Kling / 快手）适配器。
 *
 * 可灵的字段名与状态值都自成一套：模型字段叫 model_name，任务状态成功值是
 * `succeed`（不是 succeeded），结果藏在 task_result.videos[0].url。
 * 图片与视频的任务查询端点也不同，因此这里按 kind 选择默认查询路径。
 */
internal class KlingMediaAdapter : AiMediaProtocolAdapter {

    override fun buildSubmitRequest(setting: AiProviderSetting, spec: AiMediaGenerateSpec): AiMediaRequest {
        val protocol = AiMediaProtocol.KLING
        val headers = AiMediaEndpoints.mediaHeaders(setting, protocol)
        return if (spec.kind == AiMediaKind.IMAGE) {
            buildImageRequest(setting, spec.image, headers)
        } else {
            buildVideoRequest(setting, spec, headers)
        }
    }

    private fun buildImageRequest(
        setting: AiProviderSetting,
        params: AiImageParams,
        headers: Map<String, String>
    ): AiMediaRequest {
        val body = JsonObject().apply {
            addProperty("model_name", setting.modelFor(AiMediaKind.IMAGE))
            addProperty("prompt", params.prompt)
            params.negativePrompt?.takeIf { it.isNotBlank() }?.let { addProperty("negative_prompt", it) }
            params.aspectRatio?.takeIf { it.isNotBlank() }?.let { addProperty("aspect_ratio", it) }
            addProperty("n", params.n.coerceIn(1, 4))
            params.seed?.let { addProperty("seed", it) }
        }
        return AiMediaRequest(
            url = AiMediaEndpoints.imagePath(setting, AiMediaProtocol.KLING),
            method = "POST",
            headers = headers,
            json = body
        )
    }

    private fun buildVideoRequest(
        setting: AiProviderSetting,
        spec: AiMediaGenerateSpec,
        headers: Map<String, String>
    ): AiMediaRequest {
        val params = spec.video
        val body = JsonObject().apply {
            addProperty("model_name", setting.modelFor(AiMediaKind.VIDEO))
            addProperty("prompt", params.prompt)
            params.negativePrompt?.takeIf { it.isNotBlank() }?.let { addProperty("negative_prompt", it) }
            params.aspectRatio?.takeIf { it.isNotBlank() }?.let { addProperty("aspect_ratio", it) }
            params.durationSeconds?.let { addProperty("duration", it.toString()) }
            params.seed?.let { addProperty("seed", it) }
            spec.frames.firstFrame?.let { payload ->
                addProperty("image", AiMediaReferenceResolver.toDataUri(payload.bytes, payload.mimeType))
            }
            spec.frames.lastFrame?.let { payload ->
                addProperty("image_tail", AiMediaReferenceResolver.toDataUri(payload.bytes, payload.mimeType))
            }
        }
        return AiMediaRequest(
            url = AiMediaEndpoints.videoPath(setting, AiMediaProtocol.KLING),
            method = "POST",
            headers = headers,
            json = body
        )
    }

    override fun parseSubmit(json: JsonObject, spec: AiMediaGenerateSpec): AiMediaSubmit {
        val direct = AiMediaResponseParser.collectResults(json, spec.kind)
        if (direct.isNotEmpty()) return AiMediaSubmit.Completed(direct)
        val state = AiMediaResponseParser.findState(json)
        if (state == AiMediaTaskState.FAILED) {
            error(AiMediaResponseParser.findError(json) ?: "生成任务提交后立即失败")
        }
        val taskId = AiMediaResponseParser.findTaskId(json)
            ?: error("提交成功但未返回 task_id：${json.toString().take(300)}")
        return AiMediaSubmit.Async(taskId, json.toString().take(1000))
    }

    override fun buildQueryRequest(
        setting: AiProviderSetting,
        taskId: String,
        spec: AiMediaGenerateSpec
    ): AiMediaRequest {
        val protocol = AiMediaProtocol.KLING
        val defaultPath = if (spec.kind == AiMediaKind.IMAGE) {
            "/images/generations/{id}"
        } else {
            protocol.defaultTaskPath
        }
        val rawPath = setting.videoTaskPath.ifBlank { defaultPath }
        return AiMediaRequest(
            url = buildAiApiEndpoint(setting.baseUrl, rawPath.replace("{id}", taskId)),
            method = "GET",
            headers = AiMediaEndpoints.mediaHeaders(setting, protocol)
        )
    }

    override fun parseQuery(json: JsonObject, spec: AiMediaGenerateSpec): AiMediaPoll {
        val state = AiMediaResponseParser.findState(json)
        val error = AiMediaResponseParser.findError(json)
        if (state == AiMediaTaskState.SUCCEEDED) {
            val results = AiMediaResponseParser.collectResults(json, spec.kind)
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
}
