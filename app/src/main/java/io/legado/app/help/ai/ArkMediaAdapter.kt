package io.legado.app.help.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 火山方舟（Seedance / Seedream）适配器。
 *
 * 图片接口与 OpenAI 兼容（/images/generations），但视频完全不同：
 * prompt 不在顶层，而是放在 content 数组里，且比例/时长等参数以
 * `--ratio 16:9 --dur 5` 的形式拼在文本后面。首帧用 content 数组中的
 * image_url 项表达，role 区分 first_frame / last_frame。
 */
internal class ArkMediaAdapter : AiMediaProtocolAdapter {

    override fun buildSubmitRequest(setting: AiProviderSetting, spec: AiMediaGenerateSpec): AiMediaRequest {
        val protocol = AiMediaProtocol.ARK
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
            addProperty("model", setting.modelFor(AiMediaKind.IMAGE))
            addProperty("prompt", params.prompt)
            addProperty("response_format", "url")
            if (params.n > 1) addProperty("n", params.n)
            params.size?.takeIf { it.isNotBlank() }?.let { addProperty("size", it) }
            params.seed?.let { addProperty("seed", it) }
        }
        return AiMediaRequest(
            url = AiMediaEndpoints.imagePath(setting, AiMediaProtocol.ARK),
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
        val textBuilder = StringBuilder(params.prompt)
        params.aspectRatio?.takeIf { it.isNotBlank() }?.let { textBuilder.append(" --ratio $it") }
        params.durationSeconds?.let { textBuilder.append(" --dur $it") }
        params.seed?.let { textBuilder.append(" --seed $it") }

        val content = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", textBuilder.toString())
            })
            spec.frames.firstFrame?.let { payload ->
                add(JsonObject().apply {
                    addProperty("type", "image_url")
                    add("image_url", JsonObject().apply {
                        addProperty("url", AiMediaReferenceResolver.toDataUri(payload.bytes, payload.mimeType))
                    })
                    addProperty("role", "first_frame")
                })
            }
            spec.frames.lastFrame?.let { payload ->
                add(JsonObject().apply {
                    addProperty("type", "image_url")
                    add("image_url", JsonObject().apply {
                        addProperty("url", AiMediaReferenceResolver.toDataUri(payload.bytes, payload.mimeType))
                    })
                    addProperty("role", "last_frame")
                })
            }
        }
        val body = JsonObject().apply {
            addProperty("model", setting.modelFor(AiMediaKind.VIDEO))
            add("content", content)
        }
        return AiMediaRequest(
            url = AiMediaEndpoints.videoPath(setting, AiMediaProtocol.ARK),
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
            ?: error("提交成功但未返回任务 ID：${json.toString().take(300)}")
        return AiMediaSubmit.Async(taskId, json.toString().take(1000))
    }

    override fun buildQueryRequest(
        setting: AiProviderSetting,
        taskId: String,
        spec: AiMediaGenerateSpec
    ): AiMediaRequest {
        val protocol = AiMediaProtocol.ARK
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
}
