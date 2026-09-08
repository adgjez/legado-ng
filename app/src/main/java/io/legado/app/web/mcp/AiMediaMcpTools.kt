package io.legado.app.web.mcp

import com.google.gson.JsonObject
import io.legado.app.help.ai.AiConfig
import io.legado.app.help.ai.AiImageParams
import io.legado.app.help.ai.AiAudioParams
import io.legado.app.help.ai.AiMediaComposer
import io.legado.app.help.ai.AiMediaError
import io.legado.app.help.ai.AiMediaHelper
import io.legado.app.help.ai.AiMediaKind
import io.legado.app.help.ai.AiMediaReference
import io.legado.app.help.ai.AiVideoParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * AI 媒体生成 MCP 工具：让 AI 阅读助手 / Agent 编排能直接调用图片、视频生成。
 *
 * 这两个工具的 side effect 属于 APP_WRITE（会产生媒体文件并写入历史），已在
 * [McpInternalToolCatalog] 中登记为需要用户确认的能力。
 *
 * AI 漫画 / AI 漫剧的 Skill 也走这里：传 project_id / shot_index 即可把分镜产物归到
 * 同一项目下，由 Skill 负责串联角色一致性与镜头衔接。
 */
object AiMediaMcpTools {

    private val IMAGE_TOOL = "ai_media_generate_image"
    private val VIDEO_TOOL = "ai_media_generate_video"
    private val LIST_PROJECT_TOOL = "ai_media_list_project"
    private val CREATE_PROJECT_TOOL = "ai_media_create_project"
    private val AUDIO_TOOL = "ai_media_generate_audio"
    private val COMPOSE_TOOL = "ai_media_compose"

    fun supports(name: String): Boolean =
        name == IMAGE_TOOL || name == VIDEO_TOOL || name == LIST_PROJECT_TOOL ||
            name == CREATE_PROJECT_TOOL || name == AUDIO_TOOL || name == COMPOSE_TOOL

    fun call(
        name: String,
        arguments: JsonObject,
        executionContext: McpToolExecutionContext? = null
    ): Map<String, Any?>? {
        return when (name) {
            IMAGE_TOOL -> generateImage(arguments)
            VIDEO_TOOL -> generateVideo(arguments)
            LIST_PROJECT_TOOL -> listProject(arguments)
            CREATE_PROJECT_TOOL -> createProject(arguments)
            AUDIO_TOOL -> generateAudio(arguments)
            COMPOSE_TOOL -> runCatching { compose(arguments) }.getOrElse { e ->
                toolResult(
                    ok = false,
                    upstreamEndpoint = "native://aiMedia",
                    normalizedData = null,
                    warnings = listOf(e.localizedMessage ?: e.message ?: "拼装失败")
                )
            }
            else -> null
        }
    }

    /** 创建/确保一个 named 项目，漫画/漫剧开工第一步调用 */
    private fun createProject(arguments: JsonObject): Map<String, Any?> {
        val id = arguments.get("project_id").asRequiredString("project_id")
        val name = arguments.get("name").asStringOrNull().orEmpty()
        val kind = arguments.get("kind").asStringOrNull().orEmpty()
        return runCatching {
            val project = AiMediaHelper.ensureProject(id, name, kind)
            toolResult(
                ok = true,
                upstreamEndpoint = "native://aiMedia",
                normalizedData = mapOf(
                    "project_id" to project.id,
                    "name" to project.name,
                    "kind" to project.kind
                )
            )
        }.getOrElse { e ->
            toolResult(ok = false, upstreamEndpoint = "native://aiMedia",
                normalizedData = null, warnings = listOf(e.localizedMessage ?: e.message ?: "创建项目失败"))
        }
    }

    /**
     * 回读某个 project_id 下的分镜产物。
     *
     * 漫画 / 漫剧是长链生成：12 格、20 镜跑下来，模型很容易丢掉「上一格的 local_path 是什么」。
     * 垫图链一断，角色一致性立刻崩。这个工具让 Skill 随时能重新查回所有分镜的本地路径，
     * 而不必依赖上下文记忆。project_id 留空时列出所有项目及其分镜数。
     */
    private fun listProject(arguments: JsonObject): Map<String, Any?> {
        val projectId = arguments.get("project_id").asStringOrNull().orEmpty()
        return runCatching {
            val shots = runBlocking(Dispatchers.IO) {
                if (projectId.isBlank()) {
                    emptyList<io.legado.app.data.entities.AiMediaGeneration>()
                } else {
                    AiMediaHelper.historyByProject(projectId)
                }
            }
            val projects = runBlocking(Dispatchers.IO) {
                AiMediaHelper.history()
                    .groupBy { it.projectId }
                    .map { (pid, list) ->
                        mapOf<String, Any?>(
                            "project_id" to pid,
                            "shot_count" to list.size,
                            "kinds" to list.map { it.kind }.distinct(),
                            "latest_created_at" to list.maxOf { it.createdAt }
                        )
                    }
                    .sortedByDescending { it["latest_created_at"] as Long }
            }
            toolResult(
                ok = true,
                upstreamEndpoint = "native://aiMedia",
                normalizedData = mapOf(
                    "project_id" to projectId,
                    "shots" to shots.map { entity ->
                        mapOf(
                            "id" to entity.id,
                            "kind" to entity.kind,
                            "shot_index" to entity.shotIndex,
                            "prompt" to entity.prompt,
                            "status" to entity.status,
                            "created_at" to entity.createdAt,
                            "local_paths" to AiMediaHelper.displayPaths(entity)
                        )
                    },
                    "projects" to projects
                )
            )
        }.getOrElse { e ->
            toolResult(
                ok = false,
                upstreamEndpoint = "native://aiMedia",
                normalizedData = null,
                warnings = listOf(e.localizedMessage ?: e.message ?: "查询失败")
            )
        }
    }

    private fun generateImage(arguments: JsonObject): Map<String, Any?> {
        val prompt = arguments.get("prompt").asRequiredString("prompt")
        val params = AiImageParams(
            prompt = prompt,
            negativePrompt = arguments.get("negative_prompt").asStringOrNull(),
            size = arguments.get("size").asStringOrNull(),
            aspectRatio = arguments.get("aspect_ratio").asStringOrNull(),
            quality = arguments.get("quality").asStringOrNull(),
            style = arguments.get("style").asStringOrNull(),
            n = arguments.get("n").asIntOrNull() ?: 1,
            seed = arguments.get("seed").asLongOrNull(),
            references = parseReferences(arguments.get("references"))
        )
        val providerId = arguments.get("provider_id").asStringOrNull() ?: AiConfig.mediaImageProviderId
        val modelId = arguments.get("model").asStringOrNull() ?: AiConfig.mediaImageModelId.ifBlank { null }
        val projectId = arguments.get("project_id").asStringOrNull().orEmpty()
        val shotIndex = arguments.get("shot_index").asIntOrNull() ?: 0
        return runCatching {
            val entity = runBlocking(Dispatchers.IO) {
                AiMediaHelper.generateImage(
                    params = params,
                    providerId = providerId,
                    modelId = modelId,
                    projectId = projectId,
                    shotIndex = shotIndex
                )
            }
            toolResult(
                ok = true,
                upstreamEndpoint = "native://aiMedia",
                normalizedData = mediaNormalized(entity)
            )
        }.getOrElse { e ->
            toolResult(
                ok = false,
                upstreamEndpoint = "native://aiMedia",
                normalizedData = null,
                warnings = listOf(e.localizedMessage ?: e.message ?: "生成失败")
            )
        }
    }

    private fun generateVideo(arguments: JsonObject): Map<String, Any?> {
        val prompt = arguments.get("prompt").asRequiredString("prompt")
        val params = AiVideoParams(
            prompt = prompt,
            negativePrompt = arguments.get("negative_prompt").asStringOrNull(),
            durationSeconds = arguments.get("duration_seconds").asIntOrNull(),
            aspectRatio = arguments.get("aspect_ratio").asStringOrNull(),
            resolution = arguments.get("resolution").asStringOrNull(),
            seed = arguments.get("seed").asLongOrNull(),
            firstFrame = arguments.get("first_frame").asStringOrNull()?.let { AiMediaReference(source = it) },
            lastFrame = arguments.get("last_frame").asStringOrNull()?.let { AiMediaReference(source = it) }
        )
        val providerId = arguments.get("provider_id").asStringOrNull() ?: AiConfig.mediaVideoProviderId
        val modelId = arguments.get("model").asStringOrNull() ?: AiConfig.mediaVideoModelId.ifBlank { null }
        val projectId = arguments.get("project_id").asStringOrNull().orEmpty()
        val shotIndex = arguments.get("shot_index").asIntOrNull() ?: 0
        return runCatching {
            val entity = runBlocking(Dispatchers.IO) {
                AiMediaHelper.generateVideo(
                    params = params,
                    providerId = providerId,
                    modelId = modelId,
                    projectId = projectId,
                    shotIndex = shotIndex
                )
            }
            toolResult(
                ok = true,
                upstreamEndpoint = "native://aiMedia",
                normalizedData = mediaNormalized(entity)
            )
        }.getOrElse { e ->
            toolResult(
                ok = false,
                upstreamEndpoint = "native://aiMedia",
                normalizedData = null,
                warnings = listOf(e.localizedMessage ?: e.message ?: "生成失败")
            )
        }
    }

    private fun generateAudio(arguments: JsonObject): Map<String, Any?> {
        val params = AiAudioParams(
            text = arguments.get("text").asRequiredString("text"),
            voiceId = arguments.get("voice_id").asStringOrNull(),
            speed = arguments.get("speed").asFloatOrNull(),
            pitch = arguments.get("pitch").asFloatOrNull(),
            language = arguments.get("language").asStringOrNull()
        )
        val projectId = arguments.get("project_id").asStringOrNull().orEmpty()
        val shotIndex = arguments.get("shot_index").asIntOrNull() ?: 0
        val result = runBlocking(Dispatchers.IO) {
            AiMediaHelper.generateAudio(params = params, projectId = projectId, shotIndex = shotIndex)
        }
        val err = result.exceptionOrNull()
        if (err != null) {
            return toolResult(
                ok = false,
                upstreamEndpoint = "native://aiMedia",
                normalizedData = null,
                warnings = listOf(err.localizedMessage ?: err.message ?: "配音失败")
            )
        }
        return toolResult(
            ok = true,
            upstreamEndpoint = "native://aiMedia",
            normalizedData = mediaNormalized(result.getOrThrow())
        )
    }

    /**
     * 成片拼装：format=comic 竖排拼长图，format=drama 用 MediaMuxer 拼接视频。
     * 漫剧片段编码不一致时（[AiMediaError.IncompatibleVideo]）回退为 storyboard 清单
     * （返回各片段本地路径 + 提示），不假装拼成。其余失败按普通错误回传。
     */
    private fun compose(arguments: JsonObject): Map<String, Any?> {
        val projectId = arguments.get("project_id").asRequiredString("project_id")
        val format = arguments.get("format").asStringOrNull().orEmpty().ifBlank { "comic" }

        val composed = runBlocking(Dispatchers.IO) {
            if (format == "drama") AiMediaComposer.composeDrama(projectId)
            else AiMediaComposer.composeComic(projectId)
        }
        val composeErr = composed.exceptionOrNull()
        if (composeErr != null) {
            if (composeErr is AiMediaError.IncompatibleVideo) {
                // 编码不一致：把分镜清单回退给 Skill，让它决定怎么衔接
                val shots = runBlocking(Dispatchers.IO) {
                    AiMediaHelper.historyByProject(projectId)
                        .filter { it.kind == AiMediaKind.VIDEO.prefValue }
                        .sortedBy { it.shotIndex }
                }
                val paths = shots.flatMap { AiMediaHelper.displayPaths(it) }
                return toolResult(
                    ok = true,
                    upstreamEndpoint = "native://aiMedia",
                    normalizedData = mapOf(
                        "storyboard" to true,
                        "message" to (composeErr.message ?: "视频片段无法拼接"),
                        "segments" to paths
                    )
                )
            }
            return toolResult(
                ok = false,
                upstreamEndpoint = "native://aiMedia",
                normalizedData = null,
                warnings = listOf(composeErr.localizedMessage ?: composeErr.message ?: "拼装失败")
            )
        }

        val file = composed.getOrNull()!!
        val kind = if (format == "drama") AiMediaKind.VIDEO else AiMediaKind.IMAGE
        val entityResult = runBlocking(Dispatchers.IO) {
            AiMediaHelper.persistComposed(kind = kind, file = file, projectId = projectId, format = format)
        }
        val entityErr = entityResult.exceptionOrNull()
        if (entityErr != null) {
            return toolResult(
                ok = false,
                upstreamEndpoint = "native://aiMedia",
                normalizedData = null,
                warnings = listOf(entityErr.localizedMessage ?: entityErr.message ?: "成片落库失败")
            )
        }
        return toolResult(
            ok = true,
            upstreamEndpoint = "native://aiMedia",
            normalizedData = mediaNormalized(entityResult.getOrThrow())
        )
    }

    private fun mediaNormalized(entity: io.legado.app.data.entities.AiMediaGeneration): Map<String, Any?> {
        return mapOf(
            "id" to entity.id,
            "kind" to entity.kind,
            "provider_id" to entity.providerId,
            "model" to entity.model,
            "prompt" to entity.prompt,
            "project_id" to entity.projectId,
            "shot_index" to entity.shotIndex,
            "created_at" to entity.createdAt,
            "results" to AiMediaHelper.parseResults(entity).map { result ->
                mapOf(
                    "local_path" to result.localPath,
                    "remote_url" to result.remoteUrl,
                    "mime_type" to result.mimeType,
                    "width" to result.width,
                    "height" to result.height,
                    "duration_seconds" to result.durationSeconds
                )
            }
        )
    }

    private fun parseReferences(element: com.google.gson.JsonElement?): List<AiMediaReference> {
        val array = element?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            item.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                ?.let { AiMediaReference(source = it) }
        }
    }

    private fun toolResult(
        ok: Boolean,
        upstreamEndpoint: String,
        normalizedData: Any?,
        rawUpstream: Any? = null,
        warnings: List<String> = emptyList(),
        sessionId: String? = null
    ): Map<String, Any?> {
        return mapOf(
            "ok" to ok,
            "upstream_endpoint" to upstreamEndpoint,
            "normalized_data" to normalizedData,
            "raw_upstream" to rawUpstream,
            "warnings" to warnings,
            "session_id" to sessionId
        )
    }

    private fun com.google.gson.JsonElement?.asStringOrNull(): String? {
        return this?.takeIf { it.isJsonPrimitive }?.asString
    }

    private fun com.google.gson.JsonElement?.asIntOrNull(): Int? {
        return this?.takeIf { it.isJsonPrimitive }?.asInt
    }

    private fun com.google.gson.JsonElement?.asLongOrNull(): Long? {
        return this?.takeIf { it.isJsonPrimitive }?.asLong
    }

    private fun com.google.gson.JsonElement?.asFloatOrNull(): Float? {
        return this?.takeIf { it.isJsonPrimitive }?.asFloat
    }

    private fun com.google.gson.JsonElement?.asRequiredString(name: String): String {
        return asStringOrNull()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("$name is required")
    }
}
