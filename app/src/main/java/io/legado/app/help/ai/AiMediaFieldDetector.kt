package io.legado.app.help.ai

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 响应字段自动识别。
 *
 * 设计目标是「用户永远不需要手写 JSONPath」：用户只需粘贴一次接口返回
 * （或点一次测试请求），这里会把所有像样的候选字段挑出来，配上中文说明和
 * 实际值预览，用户在下拉里点一下即可，路径由程序生成。
 *
 * 之所以不做成让用户填 `data[0].url`，是因为目标用户是阅读器用户而非开发者，
 * 写错一个点号就会导致批量生成（AI 漫剧几十个镜头）全线失败，排查成本极高。
 */
object AiMediaFieldDetector {

    enum class Category(val displayName: String) {
        TASK_ID("任务 ID"),
        STATUS("任务状态"),
        RESULT_URL("结果地址"),
        ERROR("错误信息")
    }

    data class Option(
        val path: String,
        val category: Category,
        /** 实际值预览，让用户确认识别结果对不对 */
        val preview: String,
        val score: Int
    ) {
        /** 下拉里展示的中文标签，值本身截短避免刷屏 */
        val label: String
            get() = "${category.displayName} · ${preview.take(48)}"
    }

    data class Preset(
        val id: String,
        val name: String,
        val taskIdPath: String,
        val statusPath: String,
        val resultPath: String,
        val errorPath: String = ""
    )

    /** 一键套用的常见风格，覆盖大部分中转站 */
    val presets: List<Preset> = listOf(
        Preset(
            id = "auto",
            name = "自动识别（推荐）",
            taskIdPath = "",
            statusPath = "",
            resultPath = ""
        ),
        Preset(
            id = "openai",
            name = "OpenAI 风格",
            taskIdPath = "id",
            statusPath = "status",
            resultPath = "data[0].url"
        ),
        Preset(
            id = "ark",
            name = "火山方舟风格",
            taskIdPath = "id",
            statusPath = "status",
            resultPath = "content.video_url"
        ),
        Preset(
            id = "dashscope",
            name = "阿里百炼风格",
            taskIdPath = "output.task_id",
            statusPath = "output.task_status",
            resultPath = "output.results[0].url"
        ),
        Preset(
            id = "kling",
            name = "可灵风格",
            taskIdPath = "data.task_id",
            statusPath = "data.task_status",
            resultPath = "data.task_result.videos[0].url"
        ),
        Preset(
            id = "agnes",
            name = "Agnes 风格",
            taskIdPath = "id",
            statusPath = "status",
            resultPath = "video_url"
        )
    )

    private val MEDIA_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".mp4", ".mov", ".webm")

    private val URL_KEYS = setOf(
        "url", "video_url", "image_url", "download_url", "result_url", "output_url",
        "content_url", "file_url", "videoUrl", "imageUrl", "downloadUrl", "resultUrl", "uri"
    )

    private val TASK_ID_KEYS = setOf(
        "task_id", "taskId", "id", "job_id", "jobId", "video_id", "name"
    )

    private val STATUS_KEYS = setOf(
        "status", "task_status", "taskStatus", "state", "job_status", "phase"
    )

    private val ERROR_KEYS = setOf(
        "message", "error_message", "fail_reason", "reason", "detail", "msg", "error"
    )

    private val STATUS_VALUES = setOf(
        "pending", "queued", "running", "processing", "succeeded", "success", "succeed",
        "completed", "failed", "failure", "error", "cancelled", "canceled", "expired"
    )

    /**
     * 解析用户粘贴的接口返回。
     *
     * @return 识别出的候选字段（已按相关度排序）；解析失败返回 null，由 UI 提示用户检查内容
     */
    fun detectFromText(text: String): List<Option>? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null
        val root = runCatching {
            JsonParser.parseString(trimmed).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull() ?: return null
        return detect(root)
    }

    fun detect(root: JsonObject): List<Option> {
        val options = ArrayList<Option>()
        walk(root, "", options)
        return options
            .filter { it.score >= 40 }
            .sortedWith(compareByDescending<Option> { it.score }.thenBy { it.path.length })
            .distinctBy { it.path + it.category.name }
    }

    private fun walk(element: JsonElement, path: String, out: ArrayList<Option>) {
        when {
            element.isJsonObject -> {
                element.asJsonObject.entrySet().forEach { (key, value) ->
                    val childPath = if (path.isEmpty()) key else "$path.$key"
                    if (value.isJsonPrimitive) {
                        classify(childPath, key, value.asString)?.let { out.add(it) }
                    } else {
                        walk(value, childPath, out)
                    }
                }
            }

            element.isJsonArray -> {
                element.asJsonArray.forEachIndexed { index, item ->
                    walk(item, "$path[$index]", out)
                }
            }
        }
    }

    private fun classify(path: String, key: String, value: String): Option? {
        val normalizedKey = key.lowercase()
        val normalizedValue = value.lowercase()
        val preview = value.replace('\n', ' ').trim()

        val urlScore = scoreUrl(normalizedKey, normalizedValue, value)
        if (urlScore > 0) {
            return Option(path, Category.RESULT_URL, preview, urlScore)
        }
        val taskScore = scoreTaskId(normalizedKey, value)
        if (taskScore > 0) {
            return Option(path, Category.TASK_ID, preview, taskScore)
        }
        val statusScore = scoreStatus(normalizedKey, normalizedValue)
        if (statusScore > 0) {
            return Option(path, Category.STATUS, preview, statusScore)
        }
        val errorScore = scoreError(normalizedKey, value)
        if (errorScore > 0) {
            return Option(path, Category.ERROR, preview, errorScore)
        }
        return null
    }

    private fun scoreUrl(normalizedKey: String, normalizedValue: String, raw: String): Int {
        var score = 0
        if (normalizedKey in URL_KEYS) score += 100
        if (normalizedValue.startsWith("http://") || normalizedValue.startsWith("https://")) {
            score += 50
            if (MEDIA_EXTENSIONS.any { normalizedValue.substringBefore('?').endsWith(it) }) {
                score += 40
            }
        } else if (raw.length > 500 && !raw.contains(' ')) {
            // 长且无空格，多半是 base64 图片载荷
            score += 30
        }
        return score
    }

    private fun scoreTaskId(normalizedKey: String, raw: String): Int {
        var score = 0
        if (normalizedKey in TASK_ID_KEYS) score += 100
        val looksLikeId = raw.length in 12..80 && raw.none { it.isWhitespace() }
        if (looksLikeId) score += 30
        return score
    }

    private fun scoreStatus(normalizedKey: String, normalizedValue: String): Int {
        var score = 0
        if (normalizedKey in STATUS_KEYS) score += 100
        if (normalizedValue in STATUS_VALUES) score += 50
        return score
    }

    private fun scoreError(normalizedKey: String, raw: String): Int {
        if (normalizedKey !in ERROR_KEYS) return 0
        var score = 60
        if (raw.isNotBlank()) score += 20
        return score
    }
}
