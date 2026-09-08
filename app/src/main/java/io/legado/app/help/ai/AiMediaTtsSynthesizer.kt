package io.legado.app.help.ai

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import splitties.init.appCtx
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * 离线配音：用 Android 原生 [TextToSpeech] 把文本合成 wav 文件。
 *
 * 零成本、不依赖任何外部服务。前提是设备装有 TTS 引擎与对应语言包（中文需要 zh-CN 数据，
 * 多数国产 ROM 自带；部分海外引擎需用户在系统设置里下载语言包）。
 *
 * 返回 [kotlin.Result]：失败携带 [AiMediaError]，由上层 [AiMediaHelper.generateAudio] 与
 * ai_media 工具显式转成失败结果，绝不假装成功。
 */
object AiMediaTtsSynthesizer {

    private const val INIT_TIMEOUT_SEC = 15L
    private const val SYNTH_TIMEOUT_SEC = 60L

    /**
     * 把文本合成到 wav 文件。**永不抛异常**：TTS 引擎是外部依赖，任何意外都收敛成
     * [Result.failure]，避免调用方漏 catch 时崩溃。
     */
    fun synthesize(params: AiAudioParams): Result<File> {
        return try {
            synthesizeInternal(params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(AiMediaError.TtsUnavailable("配音失败：${e.localizedMessage}"))
        }
    }

    /**
     * 注意：这里刻意使用 `synthesizeToFile(text, params, filename: String, utteranceId)`
     * 这个 API-1 就存在的重载，而不是 API-30 才新增的 `File` 版本——本工程 minSdk = 21，
     * 用 File 版本在 Android 5.0–10 上会直接抛 NoSuchMethodError。
     */
    @Suppress("DEPRECATION")
    private fun synthesizeInternal(params: AiAudioParams): Result<File> {
        if (params.text.isBlank()) {
            return Result.failure(AiMediaError.TtsEmptyText("配音文本不能为空"))
        }
        val dir = File(appCtx.filesDir, "ai_media").apply { if (!exists()) mkdirs() }
        val out = File(dir, "${UUID.randomUUID()}.wav")

        var tts: TextToSpeech? = null
        val initLatch = CountDownLatch(1)
        var initStatus = TextToSpeech.ERROR
        tts = TextToSpeech(appCtx) { status ->
            initStatus = status
            initLatch.countDown()
        }
        try {
            if (!initLatch.await(INIT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                return Result.failure(AiMediaError.TtsTimeout("TTS 引擎初始化超时（设备可能未安装 TTS）"))
            }
            if (initStatus != TextToSpeech.SUCCESS || tts == null) {
                return Result.failure(AiMediaError.TtsUnavailable("TTS 引擎不可用（设备未安装 TTS 或语言包缺失）"))
            }
            val engine = tts!!
            val locale = params.language?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Locale.forLanguageTag(it) }.getOrNull() }
                ?: Locale.SIMPLIFIED_CHINESE
            // setLanguage() 的返回值能反映语言包是否真的装了。不检查的话，缺包时引擎会
            // 静默回退到别的语种，念出来牛头不对马嘴还以为成功了。
            // 策略：请求的语种不可用 → 退回中文；中文也不可用 → 明确报错，绝不假装成功。
            if (!isLanguageUsable(engine.setLanguage(locale)) && locale != Locale.SIMPLIFIED_CHINESE) {
                if (!isLanguageUsable(engine.setLanguage(Locale.SIMPLIFIED_CHINESE))) {
                    return Result.failure(
                        AiMediaError.TtsUnavailable(
                            "设备缺少 TTS 语言包（需要 ${locale.toLanguageTag()} 或 zh-CN），请在系统设置中下载"
                        )
                    )
                }
            }
            params.voiceId?.takeIf { it.isNotBlank() }?.let { name ->
                engine.voices?.firstOrNull { it.name == name }?.let { engine.setVoice(it) }
            }
            engine.setSpeechRate(params.speed ?: 1f)
            engine.setPitch(params.pitch ?: 1f)

            val doneLatch = CountDownLatch(1)
            var synthError: String? = null
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onDone(utteranceId: String?) {
                    doneLatch.countDown()
                }

                override fun onError(utteranceId: String?) {
                    synthError = "TTS 合成失败"
                    doneLatch.countDown()
                }
            })
            val utteranceId = "ai_media_audio_${UUID.randomUUID()}"
            val code = engine.synthesizeToFile(params.text, null, out.absolutePath, utteranceId)
            if (code != TextToSpeech.SUCCESS) {
                return Result.failure(AiMediaError.TtsUnavailable("TTS synthesizeToFile 返回错误码 $code"))
            }
            if (!doneLatch.await(SYNTH_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                return Result.failure(AiMediaError.TtsTimeout("TTS 合成超时"))
            }
            synthError?.let { return Result.failure(AiMediaError.TtsUnavailable(it)) }
            if (!out.exists() || out.length() == 0L) {
                return Result.failure(AiMediaError.TtsUnavailable("TTS 未写出音频文件"))
            }
            return Result.success(out)
        } finally {
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
        }
    }

    /**
     * [TextToSpeech.setLanguage] 的返回值里，LANG_MISSING_DATA 与 LANG_NOT_SUPPORTED
     * 表示该语种不可用；其余取值（LANG_AVAILABLE / LANG_COUNTRY_AVAILABLE /
     * LANG_COUNTRY_VAR_AVAILABLE）都算可用。
     */
    private fun isLanguageUsable(result: Int): Boolean {
        return result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
    }
}
