package io.legado.app.help.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Google Gemini / Imagen / Veo 适配器。
 *
 * Google 与其它家差异最大的地方：
 * - 鉴权走 x-goog-api-key 而非 Bearer
 * - Imagen 用 `:predict`，Gemini 图片用 `:generateContent`，Veo 用 `:predictLongRunning`
 * - 轮询不是查任务 ID，而是对 operation 名调用 `:fetchPredictOperation`
 * - 视频结果字段是 response.generateVideoResponse.generatedSamples[0].video.uri，
 *   且下载该 uri 仍需带 api-key
 */
internal class GoogleMediaAdapter : AiMediaProtocolAdapter {

    override fun buildSubmitRequest(setting: AiProviderSetting, spec: AiMediaGenerateSpec): AiMediaRequest {
        val protocol = AiMediaProtocol.GOOGLE
        val headers = AiMediaEndpoints.mediaHeaders(setting, protocol)
        return when (spec.kind) {
            AiMediaKind.IMAGE -> {
                val model = setting.modelFor(AiMediaKind.IMAGE)
                if (isImagen(model)) {
                    buildImagenRequest(setting, spec.image, headers)
                } else {
                    buildGeminiImageRequest(setting, spec, headers)
                }
            }

            AiMediaKind.VIDEO -> buildVeoRequest(setting, spec, headers)

            AiMediaKind.AUDIO -> throw UnsupportedOperationException(
                "Google 适配器不支持音频生成（配音走 Android 原生 TextToSpeech）"
            )
        }
    }

    private fun buildImagenRequest(
        setting: AiProviderSetting,
        params: AiImageParams,
        headers: Map<String, String>
    ): AiMediaRequest {
        val body = JsonObject().apply {
            add("instances", JsonArray().apply {
                add(JsonObject().apply { addProperty("prompt", params.prompt) })
            })
            add("parameters", JsonObject().apply {
                addProperty("sampleCount", params.n.coerceIn(1, 4))
                params.aspectRatio?.takeIf { it.isNotBlank() }?.let { addProperty("aspectRatio", it) }
                params.negativePrompt?.takeIf { it.isNotBlank() }?.let { addProperty("negativePrompt", it) }
                params.seed?.let { addProperty("seed", it) }
            })
        }
        return AiMediaRequest(
            url = AiMediaEndpoints.imagePath(setting, AiMediaProtocol.GOOGLE),
            method = "POST",
            headers = headers,
            json = body
        )
    }

    private fun buildGeminiImageRequest(
        setting: AiProviderSetting,
        spec: AiMediaGenerateSpec,
        headers: Map<String, String>
    ): AiMediaRequest {
        val params = spec.image
        val parts = JsonArray().apply {
            add(JsonObject().apply { addProperty("text", params.prompt) })
            spec.references.forEach { payload ->
                add(JsonObject().apply {
                    add("inlineData", JsonObject().apply {
                        addProperty("mimeType", payload.mimeType)
                        addProperty("data", android.util.Base64.encodeToString(payload.bytes, android.util.Base64.NO_WRAP))
                    })
                })
            }
        }
        val body = JsonObject().apply {
            add("contents", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("parts", parts)
                })
            })
            add("generationConfig", JsonObject().apply {
                add("responseModalities", JsonArray().apply { add("IMAGE") })
                params.aspectRatio?.takeIf { it.isNotBlank() }?.let { ratio ->
                    add("imageConfig", JsonObject().apply { addProperty("aspectRatio", ratio) })
                }
            })
        }
        val url = AiMediaEndpoints.imagePath(setting, AiMediaProtocol.GOOGLE)
            .replace(":predict", ":generateContent")
        return AiMediaRequest(url = url, method = "POST", headers = headers, json = body)
    }

    private fun buildVeoRequest(
        setting: AiProviderSetting,
        spec: AiMediaGenerateSpec,
        headers: Map<String, String>
    ): AiMediaRequest {
        val params = spec.video
        val instance = JsonObject().apply { addProperty("prompt", params.prompt) }
        spec.frames.firstFrame?.let { payload ->
            instance.add("image", JsonObject().apply {
                addProperty("bytesBase64Encoded", android.util.Base64.encodeToString(payload.bytes, android.util.Base64.NO_WRAP))
                addProperty("mimeType", payload.mimeType)
            })
        }
        spec.frames.lastFrame?.let { payload ->
            instance.add("lastImage", JsonObject().apply {
                addProperty("bytesBase64Encoded", android.util.Base64.encodeToString(payload.bytes, android.util.Base64.NO_WRAP))
                addProperty("mimeType", payload.mimeType)
            })
        }
        val body = JsonObject().apply {
            add("instances", JsonArray().apply { add(instance) })
            add("parameters", JsonObject().apply {
                params.aspectRatio?.takeIf { it.isNotBlank() }?.let { addProperty("aspectRatio", it) }
                params.durationSeconds?.let { addProperty("durationSeconds", it) }
                params.resolution?.takeIf { it.isNotBlank() }?.let { addProperty("resolution", it) }
                params.negativePrompt?.takeIf { it.isNotBlank() }?.let { addProperty("negativePrompt", it) }
                params.seed?.let { addProperty("seed", it) }
                params.generateAudio?.let { addProperty("generateAudio", it) }
                addProperty("personGeneration", "allow_adult")
            })
        }
        return AiMediaRequest(
            url = AiMediaEndpoints.videoPath(setting, AiMediaProtocol.GOOGLE),
            method = "POST",
            headers = headers,
            json = body
        )
    }

    override fun parseSubmit(json: JsonObject, spec: AiMediaGenerateSpec): AiMediaSubmit {
        if (spec.kind == AiMediaKind.IMAGE) {
            val results = parseImages(json)
            if (results.isNotEmpty()) return AiMediaSubmit.Completed(results)
        }
        val operationName = json.stringOrNull("name")
            ?: AiMediaResponseParser.findTaskId(json)
            ?: error("Google 未返回 operation 名称：${json.toString().take(300)}")
        return AiMediaSubmit.Async(operationName, json.toString().take(1000))
    }

    override fun buildQueryRequest(
        setting: AiProviderSetting,
        taskId: String,
        spec: AiMediaGenerateSpec
    ): AiMediaRequest {
        val protocol = AiMediaProtocol.GOOGLE
        return AiMediaRequest(
            url = AiMediaEndpoints.taskUrl(setting, protocol, taskId, spec.kind),
            method = "POST",
            headers = AiMediaEndpoints.mediaHeaders(setting, protocol),
            json = JsonObject().apply { addProperty("operationName", taskId) }
        )
    }

    override fun parseQuery(json: JsonObject, spec: AiMediaGenerateSpec): AiMediaPoll {
        val state = AiMediaResponseParser.findState(json)
        val error = AiMediaResponseParser.findError(json)
        if (state == AiMediaTaskState.SUCCEEDED) {
            val results = if (spec.kind == AiMediaKind.VIDEO) {
                parseVideos(json).ifEmpty { AiMediaResponseParser.collectResults(json, spec.kind) }
            } else {
                parseImages(json).ifEmpty { AiMediaResponseParser.collectResults(json, spec.kind) }
            }
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

    private fun parseImages(json: JsonObject): List<AiMediaResult> {
        val results = ArrayList<AiMediaResult>()
        json.arrayOrNull("predictions")?.forEach { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            obj.stringOrNull("bytesBase64Encoded")?.let { payload ->
                results.add(
                    AiMediaResult(
                        kind = AiMediaKind.IMAGE,
                        base64 = payload,
                        mimeType = obj.stringOrNull("mimeType") ?: "image/png"
                    )
                )
            }
        }
        json.arrayOrNull("candidates")?.forEach { element ->
            val candidate = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val parts = candidate.objectOrNull("content")?.arrayOrNull("parts") ?: return@forEach
            parts.forEach { partElement ->
                val part = partElement.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val inline = part.objectOrNull("inlineData") ?: return@forEach
                inline.stringOrNull("data")?.let { payload ->
                    results.add(
                        AiMediaResult(
                            kind = AiMediaKind.IMAGE,
                            base64 = payload,
                            mimeType = inline.stringOrNull("mimeType") ?: "image/png"
                        )
                    )
                }
            }
        }
        return results
    }

    private fun parseVideos(json: JsonObject): List<AiMediaResult> {
        val results = ArrayList<AiMediaResult>()
        val response = json.objectOrNull("response") ?: json
        val samples = response.arrayOrNull("generateVideoResponse")
            ?.let { it.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject }
            ?.arrayOrNull("generatedSamples")
            ?: response.arrayOrNull("generatedVideos")
            ?: response.arrayOrNull("videos")
        samples?.forEach { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val video = obj.objectOrNull("video") ?: obj
            video.stringOrNull("uri")?.let { uri ->
                results.add(AiMediaResult(kind = AiMediaKind.VIDEO, url = uri, mimeType = "video/mp4"))
            }
        }
        return results
    }

    private fun isImagen(model: String): Boolean {
        return model.contains("imagen", ignoreCase = true)
    }

    private fun JsonArray.forEach(action: (com.google.gson.JsonElement) -> Unit) {
        var index = 0
        while (index < size()) {
            action(get(index))
            index++
        }
    }
}
