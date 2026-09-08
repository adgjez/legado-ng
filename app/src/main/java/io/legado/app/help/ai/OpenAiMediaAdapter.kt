package io.legado.app.help.ai

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/**
 * OpenAI 兼容协议族适配器，同时覆盖 Agnes AI。
 *
 * 两者图片接口一致（/images/generations）；差异在视频：
 * OpenAI 系用 seconds/duration，Agnes 用 num_frames + frame_rate 且要求帧数满足 8n+1，
 * 结果字段 Agnes 放在顶层 video_url。图生图 Agnes 走 extra_body.image 数组，
 * OpenAI 走 multipart /images/edits。
 */
internal class OpenAiMediaAdapter : AiMediaProtocolAdapter {

    override fun buildSubmitRequest(setting: AiProviderSetting, spec: AiMediaGenerateSpec): AiMediaRequest {
        return if (spec.kind == AiMediaKind.IMAGE) {
            buildImageRequest(setting, spec)
        } else {
            buildVideoRequest(setting, spec)
        }
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
        val protocol = setting.resolvedMediaProtocol
        return AiMediaRequest(
            url = AiMediaEndpoints.taskUrl(setting, protocol, taskId, spec.kind),
            method = "GET",
            headers = AiMediaEndpoints.mediaHeaders(setting, protocol)
        )
    }

    override fun parseQuery(json: JsonObject, spec: AiMediaGenerateSpec): AiMediaPoll {
        val state = AiMediaResponseParser.findState(json)
        val percent = AiMediaResponseParser.findProgress(json)
        val error = AiMediaResponseParser.findError(json)
        if (state == AiMediaTaskState.SUCCEEDED) {
            val results = AiMediaResponseParser.collectResults(json, spec.kind)
            if (results.isEmpty() && error == null) {
                return AiMediaPoll(
                    state = AiMediaTaskState.RUNNING,
                    percent = percent,
                    message = "任务已完成，正在获取结果"
                )
            }
            return AiMediaPoll(state = state, percent = 100, message = null, results = results)
        }
        return AiMediaPoll(state = state, percent = percent, message = error)
    }

    private fun buildImageRequest(setting: AiProviderSetting, spec: AiMediaGenerateSpec): AiMediaRequest {
        val params = spec.image
        val protocol = setting.resolvedMediaProtocol
        if (spec.references.isNotEmpty() && protocol == AiMediaProtocol.AGNES) {
            return buildAgnesImageEdit(setting, params, spec.references)
        }
        if (spec.references.isNotEmpty()) {
            return buildImageEdit(setting, params, spec.references)
        }
        val body = JsonObject().apply {
            addProperty("model", setting.modelFor(AiMediaKind.IMAGE))
            addProperty("prompt", params.prompt)
            if (params.n > 1) addProperty("n", params.n)
            params.size?.takeIf { it.isNotBlank() }?.let { addProperty("size", it) }
            params.aspectRatio?.takeIf { it.isNotBlank() }?.let { addProperty("aspect_ratio", it) }
            params.quality?.takeIf { it.isNotBlank() }?.let { addProperty("quality", it) }
            params.style?.takeIf { it.isNotBlank() }?.let { addProperty("style", it) }
            params.seed?.let { addProperty("seed", it) }
            applyExtra(params.extra)
        }
        return AiMediaRequest(
            url = AiMediaEndpoints.imagePath(setting, protocol),
            method = "POST",
            headers = AiMediaEndpoints.mediaHeaders(setting, protocol),
            json = body
        )
    }

    private fun buildImageEdit(
        setting: AiProviderSetting,
        params: AiImageParams,
        references: List<AiMediaReferenceResolver.AiMediaReferencePayload>
    ): AiMediaRequest {
        val protocol = setting.resolvedMediaProtocol
        val files = references.map { payload ->
            AiMediaMultipartFile(
                fieldName = "image",
                fileName = payload.fileName,
                mimeType = payload.mimeType,
                bytes = payload.bytes
            )
        }
        val fields = buildList {
            add("model" to setting.modelFor(AiMediaKind.IMAGE))
            add("prompt" to params.prompt)
            if (params.n > 1) add("n" to params.n.toString())
            params.size?.takeIf { it.isNotBlank() }?.let { add("size" to it) }
            params.quality?.takeIf { it.isNotBlank() }?.let { add("quality" to it) }
        }
        return AiMediaRequest(
            url = AiMediaEndpoints.imageEditsPath(setting),
            method = "POST",
            headers = AiMediaEndpoints.mediaHeaders(setting, protocol),
            multipart = AiMediaMultipart(fields = fields, files = files)
        )
    }

    private fun buildAgnesImageEdit(
        setting: AiProviderSetting,
        params: AiImageParams,
        references: List<AiMediaReferenceResolver.AiMediaReferencePayload>
    ): AiMediaRequest {
        val protocol = AiMediaProtocol.AGNES
        val images = JsonArray().apply {
            references.forEach { payload ->
                add(AiMediaReferenceResolver.toDataUri(payload.bytes, payload.mimeType))
            }
        }
        val body = JsonObject().apply {
            addProperty("model", setting.modelFor(AiMediaKind.IMAGE))
            addProperty("prompt", params.prompt)
            params.size?.takeIf { it.isNotBlank() }?.let { addProperty("size", it) }
            params.aspectRatio?.takeIf { it.isNotBlank() }?.let { addProperty("aspect_ratio", it) }
            params.seed?.let { addProperty("seed", it) }
            add("extra_body", JsonObject().apply {
                add("image", images)
                addProperty("response_format", "url")
            })
            applyExtra(params.extra)
        }
        return AiMediaRequest(
            url = AiMediaEndpoints.imagePath(setting, protocol),
            method = "POST",
            headers = AiMediaEndpoints.mediaHeaders(setting, protocol),
            json = body
        )
    }

    private fun buildVideoRequest(setting: AiProviderSetting, spec: AiMediaGenerateSpec): AiMediaRequest {
        val params = spec.video
        val protocol = setting.resolvedMediaProtocol
        val body = JsonObject().apply {
            addProperty("model", setting.modelFor(AiMediaKind.VIDEO))
            addProperty("prompt", params.prompt)
            when (protocol) {
                AiMediaProtocol.AGNES -> {
                    val (width, height) = dimensionsFor(params)
                    addProperty("width", width)
                    addProperty("height", height)
                    addProperty("num_frames", frameCountFor(params))
                    addProperty("frame_rate", params.fps ?: 24)
                    params.seed?.let { addProperty("seed", it) }
                    if (spec.frames.firstFrame != null || spec.frames.lastFrame != null) {
                        add("extra_body", JsonObject().apply {
                            add("image", JsonArray().apply {
                                spec.frames.firstFrame?.let {
                                    add(AiMediaReferenceResolver.toDataUri(it.bytes, it.mimeType))
                                }
                                spec.frames.lastFrame?.let {
                                    add(AiMediaReferenceResolver.toDataUri(it.bytes, it.mimeType))
                                }
                            })
                            // 双图即首尾帧转场，单图即图生视频
                            if (spec.frames.firstFrame != null && spec.frames.lastFrame != null) {
                                addProperty("mode", "keyframes")
                            }
                        })
                    }
                }

                else -> {
                    params.aspectRatio?.takeIf { it.isNotBlank() }?.let { addProperty("aspect_ratio", it) }
                    params.size?.takeIf { it.isNotBlank() }?.let { addProperty("size", it) }
                    params.durationSeconds?.let {
                        addProperty("seconds", it)
                        addProperty("duration", it)
                    }
                    params.resolution?.takeIf { it.isNotBlank() }?.let { addProperty("resolution", it) }
                    params.seed?.let { addProperty("seed", it) }
                    spec.frames.firstFrame?.let { payload ->
                        addProperty("image", AiMediaReferenceResolver.toDataUri(payload.bytes, payload.mimeType))
                    }
                    spec.frames.lastFrame?.let { payload ->
                        addProperty("last_frame", AiMediaReferenceResolver.toDataUri(payload.bytes, payload.mimeType))
                    }
                }
            }
            applyExtra(params.extra)
        }
        return AiMediaRequest(
            url = AiMediaEndpoints.videoPath(setting, protocol),
            method = "POST",
            headers = AiMediaEndpoints.mediaHeaders(setting, protocol),
            json = body
        )
    }

    /** Agnes 要求帧数满足 8n+1，取值范围 81~241 */
    private fun frameCountFor(params: AiVideoParams): Int {
        val fps = params.fps ?: 24
        val duration = params.durationSeconds ?: 5
        val raw = (duration * fps).coerceAtLeast(81)
        val n = ((raw - 1) / 8).coerceIn(10, 30)
        return 8 * n + 1
    }

    private fun dimensionsFor(params: AiVideoParams): Pair<Int, Int> {
        val resolution = params.resolution?.trim().orEmpty()
        if (resolution.isNotBlank()) {
            when {
                resolution.contains("1080", ignoreCase = true) -> return 1920 to 1080
                resolution.contains("720", ignoreCase = true) -> return 1280 to 720
                resolution.contains("480", ignoreCase = true) -> return 854 to 480
            }
        }
        return when (params.aspectRatio?.trim()) {
            "9:16" -> 768 to 1152
            "1:1" -> 1024 to 1024
            "4:3" -> 1152 to 864
            "3:4" -> 864 to 1152
            else -> 1152 to 768
        }
    }

    private fun JsonObject.applyExtra(extra: Map<String, Any?>) {
        extra.forEach { (key, value) ->
            when (value) {
                null -> add(key, JsonNull.INSTANCE)
                is String -> addProperty(key, value)
                is Number -> addProperty(key, value)
                is Boolean -> addProperty(key, value)
                else -> addProperty(key, value.toString())
            }
        }
    }
}
