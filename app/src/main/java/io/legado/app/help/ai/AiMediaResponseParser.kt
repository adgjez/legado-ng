package io.legado.app.help.ai

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * 宽容响应解析。
 *
 * 各厂商的结果字段名五花八门：data[0].url、output.results[0].url、content.video_url、
 * 顶层 video_url、download_url……与其为每个厂商写死路径，不如统一做一次递归扫描，
 * 再由各适配器按自己协议优先取精确路径。
 *
 * 这样即使厂商改字段名（这在图片/视频领域很常见），老版本 App 也不会直接崩。
 */
internal object AiMediaResponseParser {

    private const val MAX_DEPTH = 8

    private val URL_KEYS = setOf(
        "url", "video_url", "image_url", "download_url", "result_url", "output_url",
        "content_url", "file_url", "videoUrl", "imageUrl", "downloadUrl", "resultUrl",
        "last_frame_url", "image",
        // Google Veo 的结果字段名是 uri
        "uri"
    )

    private val BASE64_KEYS = setOf(
        "b64_json", "base64", "image_base64", "video_base64", "data_base64",
        // Google Imagen
        "bytesBase64Encoded"
    )

    private val TASK_ID_KEYS = listOf(
        "task_id", "taskId", "id", "job_id", "jobId", "request_id", "video_id", "name", "operation"
    )

    private val STATUS_KEYS = listOf(
        "status", "task_status", "taskStatus", "state", "job_status", "phase"
    )

    private val PROGRESS_KEYS = listOf("progress", "percentage", "percent", "progress_percent")

    private val ERROR_KEYS = listOf("message", "error_message", "fail_reason", "reason", "detail", "msg")

    internal fun collectResults(root: JsonObject, kind: AiMediaKind): List<AiMediaResult> {
        val urls = LinkedHashMap<String, AiMediaResult>()
        val base64List = ArrayList<String>()
        var revisedPrompt: String? = null
        var duration: Int? = null

        root.stringOrNull("revised_prompt")?.let { revisedPrompt = it }
        scan(root, 0) { key, element ->
            if (!element.isJsonPrimitive) return@scan
            val value = element.asString
            when {
                key in URL_KEYS && value.startsWith("http", ignoreCase = true) -> {
                    urls.putIfAbsent(value, AiMediaResult(kind = kind, url = value, mimeType = mimeFor(kind)))
                }

                key in BASE64_KEYS && value.length > 200 && !value.startsWith("http", ignoreCase = true) -> {
                    base64List.add(value)
                }

                key == "duration" || key == "duration_seconds" -> {
                    duration = duration ?: element.asInt
                }
            }
        }

        val results = ArrayList<AiMediaResult>(urls.values)
        if (results.isEmpty()) {
            base64List.forEach { payload ->
                results.add(
                    AiMediaResult(
                        kind = kind,
                        base64 = payload,
                        mimeType = mimeFor(kind),
                        durationSeconds = duration
                    )
                )
            }
        }
        return results.map { it.copy(revisedPrompt = it.revisedPrompt ?: revisedPrompt) }
    }

    internal fun findTaskId(root: JsonObject): String? {
        TASK_ID_KEYS.forEach { key ->
            val value = root.get(key)?.takeIf { it.isJsonPrimitive }?.asString
            if (!value.isNullOrBlank()) return value
            root.objectOrNull("output")?.get(key)
                ?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotBlank() }?.let { return it }
            root.objectOrNull("data")?.let { data ->
                if (data.isJsonObject) {
                    data.asJsonObject.get(key)?.takeIf { it.isJsonPrimitive }?.asString
                        ?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return null
    }

    internal fun findState(root: JsonObject): AiMediaTaskState {
        val containers = listOfNotNull(root, root.objectOrNull("output"), root.objectOrNull("data"))
        STATUS_KEYS.forEach { key ->
            val value = containers.firstNotNullOfOrNull { container ->
                container.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            }
            if (!value.isNullOrBlank()) {
                val state = AiMediaTaskState.from(value)
                if (state != AiMediaTaskState.UNKNOWN) return state
            }
        }
        // Gemini 用 done + response/error 表达终态
        root.get("done")?.takeIf { it.isJsonPrimitive }?.let { done ->
            if (done.asBoolean) {
                return if (root.objectOrNull("error") != null) {
                    AiMediaTaskState.FAILED
                } else {
                    AiMediaTaskState.SUCCEEDED
                }
            }
            return AiMediaTaskState.RUNNING
        }
        return AiMediaTaskState.UNKNOWN
    }

    internal fun findProgress(root: JsonObject): Int? {
        PROGRESS_KEYS.forEach { key ->
            val element = root.get(key)?.takeIf { it.isJsonPrimitive } ?: return@forEach
            val value = element.asString.toDoubleOrNull() ?: return@forEach
            val percent = if (value <= 1.0) (value * 100).toInt() else value.toInt()
            return percent.coerceIn(0, 100)
        }
        return null
    }

    internal fun findError(root: JsonObject): String? {
        val errorObject = root.objectOrNull("error")
        if (errorObject != null) {
            errorObject.stringOrNull("message")?.takeIf { it.isNotBlank() }?.let { return it }
            errorObject.stringOrNull("msg")?.takeIf { it.isNotBlank() }?.let { return it }
            errorObject.toString().takeIf { it.isNotBlank() }?.let { return it.take(300) }
        }
        root.stringOrNull("code")?.let { code ->
            if (code != "10000" && code != "0" && code != "success" && code != "Success") {
                ERROR_KEYS.forEach { key ->
                    root.stringOrNull(key)?.takeIf { it.isNotBlank() }?.let { return "$code: $it" }
                }
                return code
            }
        }
        ERROR_KEYS.forEach { key ->
            root.objectOrNull("output")?.stringOrNull(key)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    internal fun mimeFor(kind: AiMediaKind): String {
        return if (kind == AiMediaKind.VIDEO) "video/mp4" else "image/png"
    }

    /** 结果对象里可能带尺寸信息，尽力提取 */
    internal fun collectSize(root: JsonObject): Pair<Int?, Int?> {
        var width = root.stringOrNull("width")?.toIntOrNull()
        var height = root.stringOrNull("height")?.toIntOrNull()
        if (width == null || height == null) {
            val size = root.stringOrNull("size")
            if (!size.isNullOrBlank()) {
                val parts = size.split('*', 'x', 'X').mapNotNull { it.trim().toIntOrNull() }
                if (parts.size >= 2) {
                    width = width ?: parts[0]
                    height = height ?: parts[1]
                }
            }
        }
        return width to height
    }

    private fun scan(
        element: JsonElement,
        depth: Int,
        onPrimitive: (String, JsonPrimitive) -> Unit
    ) {
        if (depth > MAX_DEPTH) return
        when {
            element.isJsonObject -> {
                element.asJsonObject.entrySet().forEach { (key, value) ->
                    if (value.isJsonPrimitive) {
                        onPrimitive(key, value.asJsonPrimitive)
                    } else {
                        scan(value, depth + 1, onPrimitive)
                    }
                }
            }

            element.isJsonArray -> {
                element.asJsonArray.forEach { scan(it, depth + 1, onPrimitive) }
            }
        }
    }

    private fun JsonArray.forEach(action: (JsonElement) -> Unit) {
        var index = 0
        while (index < size()) {
            action(get(index))
            index++
        }
    }
}
