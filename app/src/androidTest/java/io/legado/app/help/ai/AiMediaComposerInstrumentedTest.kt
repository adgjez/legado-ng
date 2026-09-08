package io.legado.app.help.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiMediaGeneration
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * AiMediaComposer 的端到端行为测试（真机 / 模拟器上运行，需要真实 Bitmap 解码与文件写出）。
 *
 * 覆盖：
 * - composeComic 把同一 project 下的图片分镜按 shotIndex 竖排拼长图（尺寸正确）；
 * - 空项目 / 无分镜 → Result.failure(AiMediaError.EmptyProject)；
 * - 自我嵌套防护：第二次合成不会把首版长图当成一页再拼进去；
 * - composeDrama 空项目同样按类型化错误失败；
 * - 坏输入不崩、不留孤儿：损坏 mp4 / 损坏 png 都收敛成 Result.failure，且半成品成片会被删掉。
 *
 * 注：composeDrama 的「编码不一致 → 回退 storyboard」路径需要真实 mp4 片段（不同分辨率），
 * 难以在单测里伪造，留由真机联调验证。
 */
@RunWith(AndroidJUnit4::class)
class AiMediaComposerInstrumentedTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val genDao get() = appDb.aiMediaGenerationDao

    private fun writePng(w: Int, h: Int): File {
        val file = File(ctx.filesDir, "compose_test_${UUID.randomUUID()}.png")
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.RED)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file
    }

    /** 造一个「扩展名对但内容完全是垃圾」的文件，用来触发解码/解封装失败 */
    private fun writeBogusFile(ext: String): File {
        val file = File(ctx.filesDir, "compose_test_${UUID.randomUUID()}.$ext")
        file.writeText("this is definitely not a valid $ext")
        return file
    }

    private fun aiMediaMp4Count(): Int =
        File(ctx.filesDir, "ai_media").listFiles()?.count { it.name.endsWith(".mp4") } ?: 0

    private fun insertImageGen(projectId: String, shotIndex: Int, file: File): String {
        val id = UUID.randomUUID().toString()
        val entity = AiMediaGeneration(
            id = id,
            kind = AiMediaKind.IMAGE.prefValue,
            providerId = "test",
            model = "m",
            prompt = "p",
            negativePrompt = "",
            paramJson = "{}",
            resultsJson = GSON.toJson(
                listOf(mapOf("localPath" to file.absolutePath, "mimeType" to "image/png"))
            ),
            projectId = projectId,
            shotIndex = shotIndex,
            seed = 0,
            durationSeconds = 0,
            width = 0,
            height = 0,
            revisedPrompt = "",
            status = "success",
            errorMessage = "",
            createdAt = System.currentTimeMillis()
        )
        genDao.insert(entity)
        return id
    }

    private fun insertVideoGen(projectId: String, shotIndex: Int, file: File): String {
        val id = UUID.randomUUID().toString()
        val entity = AiMediaGeneration(
            id = id,
            kind = AiMediaKind.VIDEO.prefValue,
            providerId = "test",
            model = "m",
            prompt = "p",
            negativePrompt = "",
            paramJson = "{}",
            resultsJson = GSON.toJson(
                listOf(mapOf("localPath" to file.absolutePath, "mimeType" to "video/mp4"))
            ),
            projectId = projectId,
            shotIndex = shotIndex,
            seed = 0,
            durationSeconds = 0,
            width = 0,
            height = 0,
            revisedPrompt = "",
            status = "success",
            errorMessage = "",
            createdAt = System.currentTimeMillis()
        )
        genDao.insert(entity)
        return id
    }

    private fun pngHeight(file: File): Int =
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inJustDecodeBounds = true }).outHeight

    @Test
    fun composeComic_stacksImagesVertically() {
        val pid = "comic_${UUID.randomUUID()}"
        insertImageGen(pid, 0, writePng(300, 100))
        insertImageGen(pid, 1, writePng(100, 300))

        val result = AiMediaComposer.composeComic(pid)
        assertTrue(result.isSuccess)
        val out = result.getOrThrow()
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out.absolutePath, opts)
        assertEquals(300, opts.outWidth) // 取最大宽，无需缩放
        assertEquals(400, opts.outHeight) // 100 + 300 竖排
    }

    @Test
    fun composeComic_emptyProject_fails() {
        val pid = "comic_empty_${UUID.randomUUID()}"
        val result = AiMediaComposer.composeComic(pid)
        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull() is AiMediaError.EmptyProject)
    }

    @Test
    fun composeComic_excludesPreviousComposedOutput() {
        // 自我嵌套防护：第二次合成不应把首版长图当成一页拼进去，否则高度会翻倍
        val pid = "comic_nest_${UUID.randomUUID()}"
        insertImageGen(pid, 0, writePng(200, 200))
        insertImageGen(pid, 1, writePng(200, 200))

        val first = AiMediaComposer.composeComic(pid).getOrThrow()
        val second = AiMediaComposer.composeComic(pid).getOrThrow()
        assertEquals(
            "第二次合成不应把首版长图再拼进去",
            pngHeight(first), pngHeight(second)
        )
    }

    @Test
    fun composeDrama_emptyProject_fails() {
        val pid = "drama_empty_${UUID.randomUUID()}"
        val result = AiMediaComposer.composeDrama(pid)
        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull() is AiMediaError.EmptyProject)
    }

    /**
     * 回归：MediaExtractor / MediaMuxer 对坏文件是「直接抛异常」的。
     * 异常若逃出 [AiMediaComposer.composeDrama]，媒体库页的 LaunchedEffect 就会崩掉整个 App。
     * 这里断言它会收敛成 Result.failure，并且不留下没落库的半成品 mp4。
     */
    @Test
    fun composeDrama_corruptSegment_failsAndCleansUp() {
        val pid = "drama_corrupt_${UUID.randomUUID()}"
        insertVideoGen(pid, 0, writeBogusFile("mp4"))
        val before = aiMediaMp4Count()

        val result = AiMediaComposer.composeDrama(pid)

        assertFalse(result.isSuccess)
        assertEquals("拼接中断不应留下未落库的孤儿 mp4", before, aiMediaMp4Count())
    }

    @Test
    fun composeComic_corruptSource_fails() {
        val pid = "comic_corrupt_${UUID.randomUUID()}"
        insertImageGen(pid, 0, writeBogusFile("png"))

        val result = AiMediaComposer.composeComic(pid)

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull() is AiMediaError.NoDecodableSource)
    }
}
