package io.legado.app.help.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 阿里百炼（DashScope / 万相）适配器。
 *
 * 与其它家三处明显不同：
 * 1. 必须带 `X-DashScope-Async: enable` 请求头，否则报不支持同步调用
 * 2. 请求体是 model / input / parameters 三段式，prompt 在 input 里
 * 3. 尺寸用 `1024*1024`（星号）而非 `1024x1024`
 *
 * 图片与视频都是异步任务制，统一走 `/tasks/{task_id}` 查询。
 */
internal class DashScopeMediaAdapter : AiMediaProtocolAdapter {

    override fun buildSubmitRequest(setting: AiProviderSetting, spec: AiMediaGenerateSpec): AiMediaRequest {
        val protocol = AiMediaProtocol.DASHSCOPE
        val headers = AiMediaEndpoints.mediaHeaders(setting, protocol)
        return if (spec.kind == AiMediaKind.IMAGE) {
            buildImageRequest(setting, spec, headers)
        } else {
            buildVideoRequest(setting, spec, headers)
        }
    }

    private fun buildImageRequest(
        setting: AiProviderSetting,
        spec: AiMediaGenerateSpec,
        headers: Map<String, String>
    ): AiMediaRequest {
        val params = spec.image
        val hasReference = spec.references.isNotEmpty()
        val input = JsonObject().apply {
            addProperty("prompt", params.prompt)
            params.negativePrompt?.takeIf { it.isNotBlank() }?.let { addProperty("negative_prompt", it) }
            if (hasReference) {
                // 万相图生图要求公网 URL；本地图片以 data uri 传入，部分模型同样接受
                add("images", JsonArray().apply {
                    spec.references.forEach { payload ->
                        add(AiMediaReferenceResolver.toDataUri(payload.bytes, payload.mimeType))
                    }
                })
            }
        }
        val body = JsonObject().apply {
            addProperty("model", setting.modelFor(AiMediaKind.IMAGE))
            add("input", input)
            add("parameters", JsonObject().apply {
                params.size?.takeIf { it.isNotBlank() }?.let { addProperty("size", normalizeSize(it)) }
                addProperty("n", params.n.coerceIn(1, 4))
                params.seed?.let { addProperty("seed", it) }
            })
        }
        val defaultPath = AiMediaProtocol.DASHSCOPE.defaultImagePath
        val rawPath = setting.imageGenerationsPath.ifBlank {
            if (hasReference) "/services/aigc/image2image/image-synthesis" else defaultPath
        }
        return AiMediaRequest(
            url = buildAiApiEndpoint(setting.baseUrl, rawPath),
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
        val input = JsonObject().apply { addProperty("prompt", params.prompt) }
        params.negativePrompt?.takeIf { it.isNotBlank() }?.let { input.addProperty("negative_prompt", it) }
        // 万相首帧仅支持公网 URL，本地图片需先转存，这里只在是远程地址时带上
        spec.video.firstFrame
            ?.takeIf { it.isRemoteUrl }
            ?.source
            ?.takeIf { it.isNotBlank() }
            ?.let { input.addProperty("first_frame_url", it) }

        val body = JsonObject().apply {
            addProperty("model", setting.modelFor(AiMediaKind.VIDEO))
            add("input", input)
            add("parameters", JsonObject().apply {
                params.size?.takeIf { it.isNotBlank() }?.let { addProperty("size", normalizeSize(it)) }
                    ?: params.resolution?.takeIf { it.isNotBlank() }?.let { addProperty("size", it) }
                params.durationSeconds?.let { addProperty("duration", it) }
                params.seed?.let { addProperty("seed", it) }
            })
        }
        return AiMediaRequest(
            url = AiMediaEndpoints.videoPath(setting, AiMediaProtocol.DASHSCOPE),
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
        val taskId = json.objectOrNull("output")?.stringOrNull("task_id")
            ?: AiMediaResponseParser.findTaskId(json)
            ?: error("提交成功但未返回 task_id：${json.toString().take(300)}")
        return AiMediaSubmit.Async(taskId, json.toString().take(1000))
    }

    override fun buildQueryRequest(
        setting: AiProviderSetting,
        taskId: String,
        spec: AiMediaGenerateSpec
    ): AiMediaRequest {
        val protocol = AiMediaProtocol.DASHSCOPE
        return AiMediaRequest(
            url = AiMediaEndpoints.taskUrl(setting, protocol, taskId, spec.kind),
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

    /** 万相使用 1024*1024 这种写法 */
    private fun normalizeSize(size: String): String {
        return size.replace('x', '*').replace('X', '*')
    }
}
