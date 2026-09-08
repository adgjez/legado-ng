package io.legado.app.help.ai

/**
 * AI 媒体生成领域模型。
 *
 * 这里只描述「单次生成」这一层能力。AI 漫画 / AI 漫剧属于更上层的编排：
 * 漫画 = 同一 projectId 下若干 shotIndex 的图片生成（靠 [AiImageParams.references]
 * 传角色垫图保持一致性），漫剧 = 若干镜头视频生成（靠 [AiVideoParams.firstFrame]
 * 承接上一镜头尾帧保持连贯）。因此本文件保留 projectId / shotIndex / 参考图 / 首尾帧
 * 等字段，后续工作流直接复用本层即可，无需改动模型与 Provider。
 */

enum class AiMediaKind(val prefValue: String) {
    @Suppress("unused")
    IMAGE("image"),

    @Suppress("unused")
    VIDEO("video"),

    @Suppress("unused")
    AUDIO("audio");

    companion object {
        fun from(value: String?): AiMediaKind {
            return entries.firstOrNull { it.prefValue == value || it.name.equals(value, ignoreCase = true) }
                ?: IMAGE
        }
    }
}

/**
 * 媒体引用，可作为参考图、角色垫图或视频首尾帧。
 *
 * @param source 远程 URL、`data:` base64 或本地 content/file uri
 * @param label 可选标签，编排层用它引用同一角色 / 同一镜头
 */
data class AiMediaReference(
    val source: String = "",
    val mimeType: String? = null,
    val label: String? = null
) {
    val isRemoteUrl: Boolean
        get() = source.startsWith("http://", ignoreCase = true) ||
            source.startsWith("https://", ignoreCase = true)

    val isDataUri: Boolean
        get() = source.startsWith("data:", ignoreCase = true)

    val isLocalUri: Boolean
        get() = source.isNotBlank() && !isRemoteUrl && !isDataUri
}

data class AiImageParams(
    val prompt: String = "",
    val negativePrompt: String? = null,
    /** 形如 1024x1024，与 aspectRatio 二选一，两者都给时优先 size */
    val size: String? = null,
    /** 形如 16:9，Gemini / Veo 系更常用 */
    val aspectRatio: String? = null,
    val quality: String? = null,
    val style: String? = null,
    val n: Int = 1,
    val seed: Long? = null,
    /** 角色垫图 / 图生图参考图，漫画场景下用于保持角色与画风一致 */
    val references: List<AiMediaReference> = emptyList(),
    /** 厂商私有参数，原样并入请求体 */
    val extra: Map<String, Any?> = emptyMap()
)

data class AiVideoParams(
    val prompt: String = "",
    val negativePrompt: String? = null,
    val durationSeconds: Int? = null,
    /** 形如 16:9 */
    val aspectRatio: String? = null,
    /** 形如 1080p */
    val resolution: String? = null,
    val fps: Int? = null,
    val seed: Long? = null,
    /** 是否同时生成音轨，部分厂商支持 */
    val generateAudio: Boolean? = null,
    /** 首帧，漫剧场景承接上一镜头尾帧以保证连贯 */
    val firstFrame: AiMediaReference? = null,
    /** 尾帧 */
    val lastFrame: AiMediaReference? = null,
    val extra: Map<String, Any?> = emptyMap()
)

/**
 * 配音参数。配音走 Android 原生 TextToSpeech（离线、零成本），不占用图片/视频 Provider。
 * @param text 要朗读的文本
 * @param voiceId 目标声音名；为空时用设备默认中文声音
 * @param speed 语速 0.5–2.0，默认 1.0
 * @param pitch 音高 0.5–2.0，默认 1.0
 * @param language BCP-47 语言标签，如 zh-CN；为空默认中文
 */
data class AiAudioParams(
    val text: String = "",
    val voiceId: String? = null,
    val speed: Float? = null,
    val pitch: Float? = null,
    val language: String? = null
)

enum class AiMediaTaskState {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN;

    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == CANCELLED

    companion object {
        fun from(value: String?): AiMediaTaskState {
            val raw = value?.trim().orEmpty()
            return when {
                raw.equals("succeeded", ignoreCase = true) ||
                    // 可灵用 succeed
                    raw.equals("succeed", ignoreCase = true) ||
                    raw.equals("success", ignoreCase = true) ||
                    raw.equals("successful", ignoreCase = true) ||
                    raw.equals("completed", ignoreCase = true) ||
                    raw.equals("complete", ignoreCase = true) ||
                    raw.equals("finished", ignoreCase = true) ||
                    raw.equals("done", ignoreCase = true) -> SUCCEEDED

                raw.equals("failed", ignoreCase = true) ||
                    raw.equals("failure", ignoreCase = true) ||
                    raw.equals("error", ignoreCase = true) -> FAILED

                raw.equals("cancelled", ignoreCase = true) ||
                    raw.equals("canceled", ignoreCase = true) -> CANCELLED

                raw.equals("running", ignoreCase = true) ||
                    raw.equals("processing", ignoreCase = true) ||
                    raw.equals("in_progress", ignoreCase = true) -> RUNNING

                raw.equals("queued", ignoreCase = true) ||
                    raw.equals("pending", ignoreCase = true) ||
                    raw.equals("waiting", ignoreCase = true) -> PENDING

                else -> UNKNOWN
            }
        }
    }
}

data class AiMediaProgress(
    val state: AiMediaTaskState = AiMediaTaskState.PENDING,
    val percent: Int? = null,
    val message: String? = null
)

data class AiMediaResult(
    val kind: AiMediaKind,
    /** 远程下载地址，可能带签名并在一段时间后失效，调用方应及时落盘 */
    val url: String? = null,
    /** base64 载荷，与 url 至少有一个非空 */
    val base64: String? = null,
    val mimeType: String? = null,
    val revisedPrompt: String? = null,
    val seed: Long? = null,
    val durationSeconds: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val rawPreview: String? = null
)
