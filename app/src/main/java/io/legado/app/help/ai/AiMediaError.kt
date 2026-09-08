package io.legado.app.help.ai

/**
 * AI 媒体业务的类型化错误。
 *
 * 跨层一律用 [kotlin.Result] 携带本类型，不再靠抛裸异常：
 * - 调用方（MCP 工具 / Compose UI）能穷尽处理，而不是在边界 `catch (e: Exception)` 糊弄；
 * - 不会出现「静默吞异常」或「未预期崩溃」；
 * - 配音/TTS、成片拼装这类外部依赖失败率高，类型化错误让上层能给出精确的中文提示。
 *
 * 因为 Kotlin 的 `Result.failure` 只收 `Throwable`，这里直接让密封类继承 [Exception]，
 * 这样既能 `Result.failure(AiMediaError.Xxx(msg))`，调用方又能用 `e is AiMediaError.Xxx`
 * 精确分流（比如漫剧编码不一致 → 回退 storyboard）。
 */
sealed class AiMediaError(message: String) : Exception(message) {

    /** 项目下没有可合成的分镜 */
    class EmptyProject(message: String) : AiMediaError(message)

    /** 素材无法解码（图片损坏 / 不是本地文件） */
    class NoDecodableSource(message: String) : AiMediaError(message)

    /** 漫剧视频编码不一致，无法用 MediaMuxer 拼接，应回退为 storyboard 清单 */
    class IncompatibleVideo(message: String) : AiMediaError(message)

    /** 成片文件未产出（编码 / 写出失败） */
    class ComposeOutputMissing(message: String) : AiMediaError(message)

    /** TTS 引擎不可用 / 语言包缺失 */
    class TtsUnavailable(message: String) : AiMediaError(message)

    /** TTS 初始化或合成超时（设备多半没装 TTS 引擎） */
    class TtsTimeout(message: String) : AiMediaError(message)

    /** 配音文本为空 */
    class TtsEmptyText(message: String) : AiMediaError(message)

    /** 本地落盘 / 读取失败 */
    class Io(message: String) : AiMediaError(message)
}
