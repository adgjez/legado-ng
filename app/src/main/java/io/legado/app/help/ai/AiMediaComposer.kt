package io.legado.app.help.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaCodec
import splitties.init.appCtx
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * AI 成片拼装：
 * - 漫画 = 同一 project 下图片分镜按 [io.legado.app.data.entities.AiMediaGeneration.shotIndex] 竖排拼长图；
 * - 漫剧 = 同一 project 下视频分镜按序号用 [MediaMuxer] 拼接成一段 mp4（要求各片段编码一致，
 *   不一致时抛 [UnsupportedOperationException]，由调用方回退到 storyboard 清单）。
 *
 * 全部用 Android 原生能力，无第三方依赖。
 *
 * [composeComic] / [composeDrama] 返回 [kotlin.Result]，失败携带 [AiMediaError]，
 * 由调用方（库页 / MCP 工具）决定怎么提示或回退，本对象不再抛裸异常。
 */
object AiMediaComposer {

    /** 长图宽度上限：压到这个尺寸以内，避免超出 Bitmap / Canvas 的边长限制 */
    private const val MAX_COMPOSE_WIDTH = 1536
    /** 长图总像素上限（约 24MP）：超过就继续收缩，控制峰值内存 */
    private const val MAX_COMPOSE_PIXELS = 24_000_000L

    /**
     * 合成产物的 providerId 标记，与 [AiMediaHelper.persistComposed] 写入的值保持一致。
     *
     * 成片与分镜同属一个 projectId，且 kind 也相同（长图是 image、成片是 video）。
     * 若不排除，第二次合成会把上一版成片当成一「页 / 一镜」再拼进去，
     * 反复点击就会产出越拼越长、自我嵌套的长图/视频。
     */
    private const val PROVIDER_NATIVE_COMPOSE = "native_compose"

    /**
     * 漫画长图拼装。
     *
     * 用「两遍扫描 + 流式绘制」代替一次性全解码：第一遍只读图片边界
     * （`inJustDecodeBounds`，不分配像素内存）算出统一缩放比与总高度；
     * 第二遍逐张解码 → 画进长图 → 立刻回收。
     * 峰值内存因此始终是「长图 + 1 张分镜」，而不是「所有分镜 + 长图」，
     * 12 格以上的长篇漫画不会 OOM。
     *
     * **永不抛异常**：内部任何意外（坏图、OOM、写出失败）都收敛成 [Result.failure]，
     * 否则调用方（Compose 协程 / MCP 工具）漏 catch 就是一次崩溃。
     * 唯一例外是 [CancellationException]——取消不是失败，必须原样抛出。
     */
    fun composeComic(projectId: String): Result<File> {
        return try {
            composeComicInternal(projectId)
        } catch (e: CancellationException) {
            // 取消不是失败：转成 Result.failure 会让协程「吞掉」取消信号，破坏结构化并发
            throw e
        } catch (e: Throwable) {
            Result.failure(AiMediaError.Io("漫画长图拼装失败：${e.localizedMessage}"))
        }
    }

    private fun composeComicInternal(projectId: String): Result<File> {
        val shots = AiMediaHelper.historyByProject(projectId)
            .filter {
                it.kind == AiMediaKind.IMAGE.prefValue &&
                    it.providerId != PROVIDER_NATIVE_COMPOSE
            }
            .sortedBy { it.shotIndex }
        if (shots.isEmpty()) {
            return Result.failure(AiMediaError.EmptyProject("该项目没有可导出的图片分镜"))
        }
        val paths = shots.mapNotNull { AiMediaHelper.displayPaths(it).firstOrNull() }
        if (paths.isEmpty()) {
            return Result.failure(AiMediaError.NoDecodableSource("没有可解码的图片分镜"))
        }

        val sizes = paths.mapNotNull { decodeBounds(it) }
        if (sizes.isEmpty()) {
            return Result.failure(AiMediaError.NoDecodableSource("没有可解码的图片分镜"))
        }

        val scale = fitScale(sizes)
        val width = (sizes.maxOf { it.first } * scale).roundToInt().coerceAtLeast(1)
        val height = sizes.sumOf { (it.second * scale).roundToInt().coerceAtLeast(1) }
            .coerceAtLeast(1)

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        var y = 0
        for (path in paths) {
            val bmp = decodeScaled(path, scale) ?: continue
            // 逐张缩放后的高度可能与预算值差 1px，兜底防止画到画布外
            if (y < height) {
                canvas.drawBitmap(bmp, (width - bmp.width) / 2f, y.toFloat(), null)
            }
            y += bmp.height
            bmp.recycle()
        }
        val dir = File(appCtx.filesDir, "ai_media").apply { if (!exists()) mkdirs() }
        val out = File(dir, "${UUID.randomUUID()}.png")
        runCatching { out.outputStream().use { result.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            .onFailure {
                result.recycle()
                // 写了一半的残缺文件不会落库，留着就是孤儿文件
                runCatching { out.delete() }
                return Result.failure(AiMediaError.Io("漫画长图写出失败：${it.localizedMessage}"))
            }
        result.recycle()
        return Result.success(out)
    }

    /** 只读宽高，不分配像素内存 */
    private fun decodeBounds(path: String): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        return if (w > 0 && h > 0) w to h else null
    }

    /** 统一缩放比：先压进宽度上限，再按总像素上限收缩（长图越高，单格缩得越多） */
    private fun fitScale(sizes: List<Pair<Int, Int>>): Float {
        val maxW = sizes.maxOf { it.first }
        val totalH = sizes.sumOf { it.second }
        var scale = if (maxW > MAX_COMPOSE_WIDTH) MAX_COMPOSE_WIDTH.toFloat() / maxW else 1f
        val pixels = (maxW * scale) * (totalH * scale)
        if (pixels > MAX_COMPOSE_PIXELS) {
            scale *= sqrt(MAX_COMPOSE_PIXELS.toDouble() / pixels.toDouble()).toFloat()
        }
        return scale.coerceIn(0.05f, 1f)
    }

    /** 按统一缩放比解码单张：先用 inSampleSize 粗降，再精确缩放到目标尺寸 */
    private fun decodeScaled(path: String, scale: Float): Bitmap? {
        val bounds = decodeBounds(path) ?: return null
        val targetW = (bounds.first * scale).roundToInt().coerceAtLeast(1)
        val targetH = (bounds.second * scale).roundToInt().coerceAtLeast(1)
        // inSampleSize 会被向下取到最近的 2 的幂，这里只用它粗降，精度交给 createScaledBitmap
        var sample = 1
        while (bounds.first / (sample * 2) >= targetW) sample *= 2
        val decoded = runCatching {
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() ?: return null
        if (decoded.width == targetW && decoded.height == targetH) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /**
     * 漫剧视频拼接。返回拼接后的 mp4 文件（包在 [kotlin.Result] 里）。
     * 若片段编码（MIME / 分辨率）不一致，返回 [AiMediaError.IncompatibleVideo]，
     * 调用方应回退为 storyboard 清单；空项目返回 [AiMediaError.EmptyProject]。
     *
     * **永不抛异常**：MediaMuxer / MediaExtractor 对坏文件很敏感，抛出来的异常若不被
     * 调用方捕获就是崩溃，这里统一收敛成 [Result.failure]（[CancellationException] 除外）。
     */
    fun composeDrama(projectId: String): Result<File> {
        return try {
            composeDramaInternal(projectId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(AiMediaError.Io("漫剧视频拼接失败：${e.localizedMessage}"))
        }
    }

    private fun composeDramaInternal(projectId: String): Result<File> {
        val shots = AiMediaHelper.historyByProject(projectId)
            .filter {
                it.kind == AiMediaKind.VIDEO.prefValue &&
                    it.providerId != PROVIDER_NATIVE_COMPOSE
            }
            .sortedBy { it.shotIndex }
        if (shots.isEmpty()) {
            return Result.failure(AiMediaError.EmptyProject("该项目没有可导出的视频分镜"))
        }
        val paths = shots.mapNotNull { AiMediaHelper.displayPaths(it).firstOrNull() }
            .filter { it.endsWith(".mp4", ignoreCase = true) }
        if (paths.isEmpty() || paths.size != shots.size) {
            return Result.failure(AiMediaError.NoDecodableSource("存在非本地 mp4 片段，无法拼接"))
        }

        val first = videoFormat(paths.first())
        for (p in paths.drop(1)) {
            val f = videoFormat(p)
            if (f.mime != first.mime || f.width != first.width || f.height != first.height) {
                return Result.failure(
                    AiMediaError.IncompatibleVideo(
                        "视频片段编码不一致（${f.width}x${f.height} vs ${first.width}x${first.height}），改用 storyboard 清单"
                    )
                )
            }
        }

        val dir = File(appCtx.filesDir, "ai_media").apply { if (!exists()) mkdirs() }
        val out = File(dir, "${UUID.randomUUID()}.mp4")
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var failure: Throwable? = null
        try {
            for (p in paths) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(p)
                    val vidIdx = (0 until extractor.trackCount).firstOrNull { idx ->
                        extractor.getTrackFormat(idx).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                    } ?: 0
                    val fmt = extractor.getTrackFormat(vidIdx)
                    extractor.selectTrack(vidIdx)
                    if (trackIndex < 0) {
                        trackIndex = muxer.addTrack(fmt)
                        muxer.start()
                    }
                    val buf = java.nio.ByteBuffer.allocate(1024 * 1024)
                    val info = MediaCodec.BufferInfo()
                    while (true) {
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) break
                        info.size = size
                        info.presentationTimeUs = extractor.sampleTime
                        info.flags = extractor.sampleFlags
                        muxer.writeSampleData(trackIndex, buf, info)
                        extractor.advance()
                    }
                } finally {
                    // 中途抛异常也要释放，否则 MediaExtractor 泄漏
                    extractor.release()
                }
            }
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
            // 拼接中断：muxer 已写出半成品 mp4 且不会落库 → 释放后删掉，别留孤儿文件
            if (failure != null) runCatching { out.delete() }
        }
        if (!out.exists() || out.length() == 0L) {
            runCatching { out.delete() }
            return Result.failure(AiMediaError.ComposeOutputMissing("视频拼接未产出有效文件"))
        }
        return Result.success(out)
    }

    /** 读取首个视频轨的格式；[setDataSource] 失败（坏文件/不支持）时也要释放 extractor，否则泄漏 */
    private fun videoFormat(path: String): MediaFormat {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(path)
            val vidIdx = (0 until ex.trackCount).firstOrNull { idx ->
                ex.getTrackFormat(idx).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: 0
            ex.getTrackFormat(vidIdx)
        } finally {
            ex.release()
        }
    }
}
