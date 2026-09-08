package io.legado.app.help.ai

import android.util.Base64
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiMediaGeneration
import io.legado.app.data.entities.AiMediaProject
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.util.UUID

/**
 * AI 媒体生成业务门面：在 [AiMediaManager] 之上负责
 * - 把远程/base64 结果落盘到 App 私有目录（远程地址常带签名、易失效，及时本地化）
 * - 写生成历史（Room），供创作页与未来的 AI 漫画/漫剧复用
 * - 历史查询、删除、按项目清理
 *
 * 编排层（漫画/漫剧 Skill）直接调这里，传入 projectId / shotIndex 即可把产物归到同一项目下。
 */
object AiMediaHelper {

    /** 落盘后的单条结果，序列化进 [AiMediaGeneration.resultsJson] */
    data class AiMediaStoredResult(
        val localPath: String = "",
        val remoteUrl: String? = null,
        val mimeType: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val durationSeconds: Int? = null,
        val seed: Long? = null
    )

    /**
     * 固定放在 filesDir 下：file_paths.xml 已声明 <files-path name="files" path="." />，
     * 这样分享 / 外部打开时才能经 FileProvider 授予临时读权限。
     *
     * 若改用 getDir("ai_media")，实际路径是 /data/data/<pkg>/app_ai_media，
     * 不在 FileProvider 的标准映射内，分享时会因 FileUriExposedException 直接崩溃。
     */
    private val mediaDir: File
        get() = File(appCtx.filesDir, "ai_media").apply { if (!exists()) mkdirs() }

    suspend fun generateImage(
        params: AiImageParams,
        providerId: String = AiProviderStore.activeProviderId(),
        modelId: String? = null,
        projectId: String = "",
        shotIndex: Int = 0,
        onProgress: suspend (AiMediaProgress) -> Unit = {}
    ): AiMediaGeneration {
        val results = AiMediaManager.generateImage(params, providerId, modelId, onProgress)
        val entity = persist(
            kind = AiMediaKind.IMAGE,
            prompt = params.prompt,
            negativePrompt = params.negativePrompt,
            imageParams = params,
            videoParams = null,
            results = results,
            providerId = providerId,
            modelId = modelId,
            projectId = projectId,
            shotIndex = shotIndex
        )
        if (projectId.isNotBlank()) touchProject(projectId)
        return entity
    }

    suspend fun generateVideo(
        params: AiVideoParams,
        providerId: String = AiProviderStore.activeProviderId(),
        modelId: String? = null,
        projectId: String = "",
        shotIndex: Int = 0,
        onProgress: suspend (AiMediaProgress) -> Unit = {}
    ): AiMediaGeneration {
        val results = AiMediaManager.generateVideo(params, providerId, modelId, onProgress)
        val entity = persist(
            kind = AiMediaKind.VIDEO,
            prompt = params.prompt,
            negativePrompt = params.negativePrompt,
            imageParams = null,
            videoParams = params,
            results = results,
            providerId = providerId,
            modelId = modelId,
            projectId = projectId,
            shotIndex = shotIndex
        )
        if (projectId.isNotBlank()) touchProject(projectId)
        return entity
    }

    /**
     * 配音：离线 Android TextToSpeech 合成 wav，产物以 [AiMediaKind.AUDIO] 落库，
     * 复用同一张历史表与媒体库（kind=audio），无需新增列。
     */
    suspend fun generateAudio(
        params: AiAudioParams,
        projectId: String = "",
        shotIndex: Int = 0
    ): Result<AiMediaGeneration> = withContext(IO) {
        AiMediaTtsSynthesizer.synthesize(params).mapCatching { file ->
            val stored = listOf(
                AiMediaStoredResult(localPath = file.absolutePath, mimeType = "audio/wav")
            )
            val entity = AiMediaGeneration(
                id = UUID.randomUUID().toString(),
                kind = AiMediaKind.AUDIO.prefValue,
                providerId = "android_tts",
                model = params.voiceId ?: "default",
                prompt = params.text,
                negativePrompt = "",
                paramJson = GSON.toJson(
                    buildMap {
                        put("voiceId", params.voiceId)
                        put("speed", params.speed)
                        put("pitch", params.pitch)
                        put("language", params.language)
                    }
                ),
                resultsJson = GSON.toJson(stored),
                projectId = projectId,
                shotIndex = shotIndex,
                seed = 0L,
                durationSeconds = 0,
                width = 0,
                height = 0,
                revisedPrompt = "",
                status = "success",
                errorMessage = "",
                createdAt = System.currentTimeMillis()
            )
            appDb.aiMediaGenerationDao.insert(entity)
            if (projectId.isNotBlank()) touchProject(projectId)
            entity
        }
    }

    /** 把已拼装好的成片文件（长图/成片 mp4）落库为一条普通历史，归入同一 project */
    suspend fun persistComposed(
        kind: AiMediaKind,
        file: File,
        projectId: String,
        format: String
    ): Result<AiMediaGeneration> = withContext(IO) {
        runCatching {
            val mimeType = if (kind == AiMediaKind.VIDEO) "video/mp4" else "image/png"
            val stored = listOf(AiMediaStoredResult(localPath = file.absolutePath, mimeType = mimeType))
            val entity = AiMediaGeneration(
                id = UUID.randomUUID().toString(),
                kind = kind.prefValue,
                providerId = "native_compose",
                model = format,
                prompt = if (format == "drama") "漫剧成片拼装" else "漫画长图拼装",
                negativePrompt = "",
                paramJson = GSON.toJson(buildMap { put("format", format); put("projectId", projectId) }),
                resultsJson = GSON.toJson(stored),
                projectId = projectId,
                shotIndex = 0,
                seed = 0L,
                durationSeconds = 0,
                width = 0,
                height = 0,
                revisedPrompt = "",
                status = "success",
                errorMessage = "",
                createdAt = System.currentTimeMillis()
            )
            appDb.aiMediaGenerationDao.insert(entity)
            if (projectId.isNotBlank()) touchProject(projectId)
            entity
        }.onFailure {
            // 落库失败 → 没有任何记录引用这个成片，留着就是「库里看不见、清理又扫不到」的孤儿文件
            runCatching { file.delete() }
        }
    }

    private suspend fun persist(
        kind: AiMediaKind,
        prompt: String,
        negativePrompt: String?,
        imageParams: AiImageParams?,
        videoParams: AiVideoParams?,
        results: List<AiMediaResult>,
        providerId: String,
        modelId: String?,
        projectId: String,
        shotIndex: Int
    ): AiMediaGeneration = withContext(IO) {
        val setting = AiProviderStore.provider(providerId)
        val model = modelId ?: setting?.modelFor(kind) ?: ""

        val ext = when (kind) {
            AiMediaKind.VIDEO -> "mp4"
            AiMediaKind.AUDIO -> "wav"
            else -> "png"
        }
        val stored = results.mapIndexed { index, result ->
            val bytes = when {
                !result.url.isNullOrBlank() -> runCatching {
                    setting?.let { AiMediaManager.downloadBytes(it, result.url) }
                }.getOrNull()
                !result.base64.isNullOrBlank() -> runCatching {
                    Base64.decode(result.base64, Base64.DEFAULT)
                }.getOrNull()
                else -> null
            }
            val localPath = bytes?.let { data ->
                val file = mediaDir.resolve("${UUID.randomUUID()}.$ext")
                file.writeBytes(data)
                file.absolutePath
            }.orEmpty()
            AiMediaStoredResult(
                localPath = localPath,
                remoteUrl = result.url,
                mimeType = result.mimeType,
                width = result.width,
                height = result.height,
                durationSeconds = result.durationSeconds,
                seed = result.seed
            )
        }
        val paramJson = GSON.toJson(
            buildMap {
                // 视频没有 size 概念，分辨率走下面的 resolution；原来的 `videoParams?.let { null }` 恒为 null
                put("size", imageParams?.size)
                put("aspectRatio", imageParams?.aspectRatio ?: videoParams?.aspectRatio)
                put("quality", imageParams?.quality)
                put("style", imageParams?.style)
                put("n", imageParams?.n)
                put("seed", imageParams?.seed ?: videoParams?.seed)
                put("durationSeconds", videoParams?.durationSeconds)
                put("resolution", videoParams?.resolution)
                put("negativePrompt", negativePrompt)
            }
        )
        val entity = AiMediaGeneration(
            id = UUID.randomUUID().toString(),
            kind = kind.prefValue,
            providerId = providerId,
            model = model,
            prompt = prompt,
            negativePrompt = negativePrompt.orEmpty(),
            paramJson = paramJson,
            resultsJson = GSON.toJson(stored),
            projectId = projectId,
            shotIndex = shotIndex,
            seed = results.firstNotNullOfOrNull { it.seed } ?: 0L,
            durationSeconds = results.firstNotNullOfOrNull { it.durationSeconds } ?: 0,
            width = results.firstNotNullOfOrNull { it.width } ?: 0,
            height = results.firstNotNullOfOrNull { it.height } ?: 0,
            revisedPrompt = results.firstNotNullOfOrNull { it.revisedPrompt }.orEmpty(),
            status = if (stored.isNotEmpty() || results.isNotEmpty()) "success" else "failed",
            errorMessage = "",
            createdAt = System.currentTimeMillis()
        )
        appDb.aiMediaGenerationDao.insert(entity)
        entity
    }

    fun history(kind: AiMediaKind? = null): List<AiMediaGeneration> {
        return if (kind == null) appDb.aiMediaGenerationDao.all()
        else appDb.aiMediaGenerationDao.allByKind(kind.prefValue)
    }

    fun historyByProject(projectId: String): List<AiMediaGeneration> {
        return appDb.aiMediaGenerationDao.allByProject(projectId)
    }

    // ===================== 项目（first-class）管理 =====================

    /**
     * 确保项目存在：不存在则创建，存在则更新名字/类型与 updatedAt。
     * 漫画/漫剧 Skill 在开工第一步调用，之后所有分镜产物都归到这个 projectId 下。
     */
    fun ensureProject(id: String, name: String, kind: String): AiMediaProject {
        val now = System.currentTimeMillis()
        val existing = appDb.aiMediaProjectDao.get(id)
        val entity = existing?.copy(
            name = name.ifBlank { existing.name },
            kind = kind.ifBlank { existing.kind },
            updatedAt = now
        ) ?: AiMediaProject(
            id = id,
            name = name.ifBlank { id },
            kind = kind.ifBlank { "mixed" },
            createdAt = now,
            updatedAt = now
        )
        appDb.aiMediaProjectDao.insert(entity)
        return appDb.aiMediaProjectDao.get(id) ?: entity
    }

    /** 重新统计分镜数并刷新封面（取首格/首镜本地路径）；项目不存在时静默跳过 */
    fun touchProject(id: String) {
        val project = appDb.aiMediaProjectDao.get(id) ?: return
        val shots = appDb.aiMediaGenerationDao.allByProject(id)
        val cover = project.coverPath.ifBlank {
            shots.firstOrNull()?.let { displayPaths(it).firstOrNull() }.orEmpty()
        }
        appDb.aiMediaProjectDao.touch(id, shots.size, cover, System.currentTimeMillis())
    }

    fun renameProject(id: String, name: String) {
        appDb.aiMediaProjectDao.rename(id, name, System.currentTimeMillis())
    }

    fun updateProjectExtra(id: String, extraJson: String) {
        appDb.aiMediaProjectDao.updateExtra(id, extraJson, System.currentTimeMillis())
    }

    fun projects(): List<AiMediaProject> = appDb.aiMediaProjectDao.all()

    fun get(id: String): AiMediaGeneration? = appDb.aiMediaGenerationDao.get(id)

    fun parseResults(entity: AiMediaGeneration): List<AiMediaStoredResult> {
        if (entity.resultsJson.isBlank()) return emptyList()
        return runCatching {
            GSON.fromJson(entity.resultsJson, Array<AiMediaStoredResult>::class.java).toList()
        }.getOrNull() ?: emptyList()
    }

    /** 解析出可用于展示/分享的本地或远程地址 */
    fun displayPaths(entity: AiMediaGeneration): List<String> {
        return parseResults(entity).mapNotNull { result ->
            result.localPath.takeIf { it.isNotBlank() } ?: result.remoteUrl
        }
    }

    fun delete(id: String) {
        val entity = appDb.aiMediaGenerationDao.get(id) ?: return
        parseResults(entity).forEach { result ->
            if (result.localPath.isNotBlank()) runCatching { File(result.localPath).delete() }
        }
        appDb.aiMediaGenerationDao.delete(id)
    }

    fun deleteProject(projectId: String) {
        appDb.aiMediaGenerationDao.allByProject(projectId).forEach { delete(it.id) }
    }

    /** 清理早于给定时间的记录，释放存储 */
    fun clearOlderThan(before: Long) {
        appDb.aiMediaGenerationDao.deleteOlderThan(before).let { deleted ->
            if (deleted > 0) pruneOrphanFiles()
        }
    }

    private fun pruneOrphanFiles() {
        val valid = appDb.aiMediaGenerationDao.all().flatMap { parseResults(it).map { r -> r.localPath } }.toSet()
        mediaDir.listFiles()?.forEach { file ->
            if (file.absolutePath !in valid) runCatching { file.delete() }
        }
    }
}
