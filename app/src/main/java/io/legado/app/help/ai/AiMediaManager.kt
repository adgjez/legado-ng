package io.legado.app.help.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.help.http.await
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 媒体生成门面。
 *
 * 对外只暴露「给我一张图 / 一段视频」，内部负责挑选协议族适配器、执行请求、
 * 轮询异步任务。AI 漫画 / AI 漫剧等编排层直接调用这里，不必关心厂商差异。
 */
object AiMediaManager {

    private val openAiAdapter by lazy { OpenAiMediaAdapter() }
    private val googleAdapter by lazy { GoogleMediaAdapter() }
    private val arkAdapter by lazy { ArkMediaAdapter() }
    private val dashScopeAdapter by lazy { DashScopeMediaAdapter() }
    private val klingAdapter by lazy { KlingMediaAdapter() }
    private val customAdapter by lazy { CustomMediaAdapter() }

    suspend fun generateImage(
        params: AiImageParams,
        providerId: String = AiProviderStore.activeProviderId(),
        modelId: String? = null,
        onProgress: suspend (AiMediaProgress) -> Unit = {}
    ): List<AiMediaResult> {
        val setting = resolveSetting(providerId, modelId, AiMediaKind.IMAGE)
        val references = params.references.mapNotNull { reference ->
            AiMediaReferenceResolver.resolve(reference, setting)
        }
        val spec = AiMediaGenerateSpec(
            setting = setting,
            kind = AiMediaKind.IMAGE,
            image = params,
            references = references
        )
        return generate(setting, spec, onProgress)
    }

    suspend fun generateVideo(
        params: AiVideoParams,
        providerId: String = AiProviderStore.activeProviderId(),
        modelId: String? = null,
        onProgress: suspend (AiMediaProgress) -> Unit = {}
    ): List<AiMediaResult> {
        val setting = resolveSetting(providerId, modelId, AiMediaKind.VIDEO)
        val firstFrame = params.firstFrame?.let { AiMediaReferenceResolver.resolve(it, setting) }
        val lastFrame = params.lastFrame?.let { AiMediaReferenceResolver.resolve(it, setting) }
        val spec = AiMediaGenerateSpec(
            setting = setting,
            kind = AiMediaKind.VIDEO,
            video = params,
            frames = AiMediaVideoFrames(firstFrame = firstFrame, lastFrame = lastFrame)
        )
        return generate(setting, spec, onProgress)
    }

    /** 下载远程结果，鉴权头按协议族带上（Google 的媒体 uri 也必须带 key） */
    suspend fun downloadBytes(setting: AiProviderSetting, url: String): ByteArray = withContext(IO) {
        val protocol = setting.resolvedMediaProtocol
        val request = Request.Builder()
            .url(url)
            .apply {
                AiMediaEndpoints.mediaHeaders(setting, protocol).forEach { (name, value) ->
                    if (name.equals("Content-Type", ignoreCase = true)) return@forEach
                    addHeader(name, value)
                }
            }
            .get()
            .build()
        val response = aiHttpClient(300).newCall(request).await()
        if (!response.isSuccessful) {
            error("下载媒体失败 HTTP ${response.code}")
        }
        response.body.bytes()
    }

    private suspend fun generate(
        setting: AiProviderSetting,
        spec: AiMediaGenerateSpec,
        onProgress: suspend (AiMediaProgress) -> Unit
    ): List<AiMediaResult> {
        val adapter = adapterFor(setting.resolvedMediaProtocol)
        onProgress(AiMediaProgress(AiMediaTaskState.PENDING, message = "提交生成任务"))
        val submitJson = execute(adapter.buildSubmitRequest(setting, spec), setting.timeoutSeconds)
        when (val submit = adapter.parseSubmit(submitJson, spec)) {
            is AiMediaSubmit.Completed -> {
                onProgress(AiMediaProgress(AiMediaTaskState.SUCCEEDED, percent = 100))
                return submit.results
            }

            is AiMediaSubmit.Async -> {
                onProgress(AiMediaProgress(AiMediaTaskState.RUNNING, message = "任务已提交，等待生成"))
                return pollUntilDone(setting, adapter, submit.taskId, spec, onProgress)
            }
        }
    }

    private suspend fun pollUntilDone(
        setting: AiProviderSetting,
        adapter: AiMediaProtocolAdapter,
        taskId: String,
        spec: AiMediaGenerateSpec,
        onProgress: suspend (AiMediaProgress) -> Unit
    ): List<AiMediaResult> {
        val timeoutSeconds = AiConfig.mediaTimeoutSeconds
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        var interval = 3000L
        var lastMessage: String? = null
        while (true) {
            delay(interval)
            if (System.currentTimeMillis() > deadline) {
                error("生成超时（${timeoutSeconds} 秒）。任务 ID：$taskId，可在设置中调大媒体生成超时。")
            }
            val json = execute(adapter.buildQueryRequest(setting, taskId, spec), 60)
            val poll = adapter.parseQuery(json, spec)
            lastMessage = poll.message ?: lastMessage
            onProgress(
                AiMediaProgress(
                    state = poll.state,
                    percent = poll.percent,
                    message = poll.message ?: lastMessage
                )
            )
            when (poll.state) {
                AiMediaTaskState.SUCCEEDED -> {
                    if (poll.results.isNotEmpty()) return poll.results
                }

                AiMediaTaskState.FAILED -> error(poll.message ?: lastMessage ?: "生成失败")

                AiMediaTaskState.CANCELLED -> error("任务已取消：$taskId")

                else -> Unit
            }
            interval = (interval + 2000L).coerceAtMost(10000L)
        }
    }

    private fun resolveSetting(
        providerId: String,
        modelId: String?,
        kind: AiMediaKind
    ): AiProviderSetting {
        val setting = AiProviderStore.provider(providerId)
            ?: error("AI provider not found: $providerId")
        check(setting.enabled) { "AI provider is disabled" }
        check(setting.supportsKind(kind)) {
            if (kind == AiMediaKind.IMAGE) "该提供商未启用图片生成" else "该提供商未启用视频生成"
        }
        val model = modelId ?: setting.modelFor(kind)
        check(model.isNotBlank()) {
            if (kind == AiMediaKind.IMAGE) "未选择图片生成模型" else "未选择视频生成模型"
        }
        return if (kind == AiMediaKind.IMAGE) {
            setting.copy(imageModel = model)
        } else {
            setting.copy(videoModel = model)
        }
    }

    private fun adapterFor(protocol: AiMediaProtocol): AiMediaProtocolAdapter {
        return when (protocol) {
            AiMediaProtocol.OPENAI, AiMediaProtocol.AGNES -> openAiAdapter
            AiMediaProtocol.GOOGLE -> googleAdapter
            AiMediaProtocol.ARK -> arkAdapter
            AiMediaProtocol.DASHSCOPE -> dashScopeAdapter
            AiMediaProtocol.KLING -> klingAdapter
            AiMediaProtocol.CUSTOM -> customAdapter
        }
    }

    private suspend fun execute(request: AiMediaRequest, timeoutSeconds: Int): JsonObject = withContext(IO) {
        val client = aiHttpClient(timeoutSeconds)
        val multipart = request.multipart
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) ->
            // multipart 的 Content-Type 要留给 OkHttp 自动带 boundary
            if (multipart != null && name.equals("Content-Type", ignoreCase = true)) return@forEach
            builder.addHeader(name, value)
        }
        if (request.method.equals("GET", ignoreCase = true)) {
            builder.get()
        } else {
            val body = multipart?.let { part ->
                val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                part.fields.forEach { (key, value) -> form.addFormDataPart(key, value) }
                part.files.forEach { file ->
                    val mediaType = file.mimeType.toMediaTypeOrNull()
                    form.addFormDataPart(
                        file.fieldName,
                        file.fileName,
                        file.bytes.toRequestBody(mediaType)
                    )
                }
                form.build()
            } ?: request.json?.let { jsonBody(it) }
            builder.post(body ?: ByteArray(0).toRequestBody(null))
        }
        val response = client.newCall(builder.build()).await()
        val text = response.body.string()
        if (!response.isSuccessful) {
            error("HTTP ${response.code}: ${text.take(500)}")
        }
        val parsed = text.takeIf { it.isNotBlank() }?.let { JsonParser.parseString(it) }
        if (parsed == null || !parsed.isJsonObject) {
            error("接口返回的不是 JSON：${text.take(300)}")
        }
        parsed.asJsonObject
    }
}
