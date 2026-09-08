package io.legado.app.help.ai

import com.google.gson.JsonObject

/**
 * 媒体生成协议族。
 *
 * 文本领域 OpenAI 兼容协议一统江湖，所以 [AiProviderSetting] 只需一个
 * chatCompletionsPath；但图片/视频领域各家差异极大 —— prompt 可能在顶层、input 里
 * 或 content 数组中，任务 ID 可能叫 id/task_id/output.task_id，结果 URL 可能在
 * data[0].url/content.video_url/output.results[0].url，光靠一个路径根本套不住。
 *
 * 因此这里按「协议族」收口：每个族一个适配器，负责请求体构造与响应解析；
 * 端点路径保留在 [AiProviderSetting] 中可被用户覆盖；[AiMediaProtocol.CUSTOM]
 * 另外支持 JSONPath 映射，用于接入尚未内置的厂商。
 */
enum class AiMediaProtocol(
    val prefValue: String,
    val displayName: String,
    val defaultImagePath: String,
    val defaultVideoPath: String,
    val defaultTaskPath: String
) {
    OPENAI(
        prefValue = "openai",
        displayName = "OpenAI 兼容",
        defaultImagePath = "/images/generations",
        defaultVideoPath = "/videos/generations",
        defaultTaskPath = "/videos/{id}"
    ),

    AGNES(
        prefValue = "agnes",
        displayName = "Agnes AI",
        defaultImagePath = "/images/generations",
        defaultVideoPath = "/videos",
        defaultTaskPath = "/videos/{id}"
    ),

    GOOGLE(
        prefValue = "google",
        displayName = "Google Gemini / Veo",
        defaultImagePath = "/models/{model}:predict",
        defaultVideoPath = "/models/{model}:predictLongRunning",
        defaultTaskPath = "/{name}:fetchPredictOperation"
    ),

    ARK(
        prefValue = "ark",
        displayName = "火山方舟 Seedance",
        defaultImagePath = "/images/generations",
        defaultVideoPath = "/contents/generations/tasks",
        defaultTaskPath = "/contents/generations/tasks/{id}"
    ),

    DASHSCOPE(
        prefValue = "dashscope",
        displayName = "阿里百炼 万相",
        defaultImagePath = "/services/aigc/text2image/image-synthesis",
        defaultVideoPath = "/services/aigc/video-generation/video-synthesis",
        defaultTaskPath = "/tasks/{id}"
    ),

    KLING(
        prefValue = "kling",
        displayName = "可灵 Kling",
        defaultImagePath = "/images/generations",
        defaultVideoPath = "/videos/text2video",
        defaultTaskPath = "/videos/{id}"
    ),

    CUSTOM(
        prefValue = "custom",
        displayName = "自定义",
        defaultImagePath = "/images/generations",
        defaultVideoPath = "/videos/generations",
        defaultTaskPath = "/videos/{id}"
    );

    companion object {
        fun from(value: String?): AiMediaProtocol {
            return entries.firstOrNull {
                it.prefValue == value || it.name.equals(value, ignoreCase = true)
            } ?: OPENAI
        }

        /** 未显式配置时按 Provider 类型推断，保证老配置升级后仍可用 */
        fun inferFrom(type: AiProviderType): AiMediaProtocol {
            return when (type) {
                AiProviderType.GOOGLE -> GOOGLE
                AiProviderType.CLAUDE -> OPENAI
                AiProviderType.OPENAI -> OPENAI
            }
        }
    }
}

/** 一次媒体 API 调用的完整描述，由适配器构造、由 [AiMediaManager] 执行 */
internal data class AiMediaRequest(
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val json: JsonObject? = null,
    val multipart: AiMediaMultipart? = null
)

internal data class AiMediaMultipart(
    val fields: List<Pair<String, String>> = emptyList(),
    val files: List<AiMediaMultipartFile> = emptyList()
)

internal data class AiMediaMultipartFile(
    val fieldName: String,
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray
)

/** 提交结果：要么同步拿到成品，要么拿到需要轮询的任务 ID */
internal sealed class AiMediaSubmit {
    data class Completed(val results: List<AiMediaResult>) : AiMediaSubmit()

    data class Async(val taskId: String, val rawPreview: String? = null) : AiMediaSubmit()
}

/** 轮询结果 */
internal data class AiMediaPoll(
    val state: AiMediaTaskState,
    val percent: Int? = null,
    val message: String? = null,
    val results: List<AiMediaResult> = emptyList()
)

/** 视频首尾帧解析后的字节数据，漫剧靠 firstFrame 承接上一镜头尾帧 */
internal data class AiMediaVideoFrames(
    val firstFrame: AiMediaReferenceResolver.AiMediaReferencePayload? = null,
    val lastFrame: AiMediaReferenceResolver.AiMediaReferencePayload? = null
)

/**
 * 一次生成请求的完整参数。
 *
 * 图片与视频共用同一套「提交 → 可能轮询 → 结果」流程。虽然 OpenAI 系图片是同步返回，
 * 但阿里万相、即梦、可灵的图片同样是异步任务制，若假设「图片必然同步」，
 * 这些厂商会直接拿不到结果。因此同步与否由适配器在 parseSubmit 中决定。
 */
internal data class AiMediaGenerateSpec(
    val setting: AiProviderSetting,
    val kind: AiMediaKind,
    val image: AiImageParams = AiImageParams(),
    val video: AiVideoParams = AiVideoParams(),
    val references: List<AiMediaReferenceResolver.AiMediaReferencePayload> = emptyList(),
    val frames: AiMediaVideoFrames = AiMediaVideoFrames()
)

/**
 * 协议族适配器。
 *
 * 新增厂商 = 新增一个实现类并在 [AiMediaManager] 注册，引擎、UI、历史与 MCP 均无需改动。
 */
internal interface AiMediaProtocolAdapter {

    fun buildSubmitRequest(setting: AiProviderSetting, spec: AiMediaGenerateSpec): AiMediaRequest

    fun parseSubmit(json: JsonObject, spec: AiMediaGenerateSpec): AiMediaSubmit

    fun buildQueryRequest(
        setting: AiProviderSetting,
        taskId: String,
        spec: AiMediaGenerateSpec
    ): AiMediaRequest

    fun parseQuery(json: JsonObject, spec: AiMediaGenerateSpec): AiMediaPoll
}
