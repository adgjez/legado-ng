package io.legado.app.help.ai

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 极简 JSONPath 取值，供自定义协议族做响应字段映射。
 *
 * 只支持点号与下标两种语法（如 `output.task_id`、`data[0].url`），
 * 不做过滤表达式 —— 配置界面越简单，批量生成（AI 漫剧几十个镜头）时越不容易出错。
 */
internal object AiMediaJsonPath {

    internal fun string(root: JsonObject?, path: String?): String? {
        val element = element(root, path) ?: return null
        if (!element.isJsonPrimitive) return null
        return element.asString
    }

    internal fun int(root: JsonObject?, path: String?): Int? {
        val element = element(root, path) ?: return null
        if (!element.isJsonPrimitive) return null
        return element.asInt
    }

    internal fun element(root: JsonObject?, path: String?): JsonElement? {
        val normalized = path?.trim().orEmpty()
        if (root == null || normalized.isBlank()) return null
        var current: JsonElement = root
        normalized.split('.')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { segment ->
                current = when {
                    current.isJsonObject -> {
                        val (key, index) = splitIndex(segment)
                        val next = current.asJsonObject.get(key) ?: return null
                        if (index == null) next else indexOf(next, index) ?: return null
                    }

                    current.isJsonArray -> {
                        val (_, index) = splitIndex(segment)
                        indexOf(current, index ?: 0) ?: return null
                    }

                    else -> return null
                }
            }
        return current
    }

    private fun splitIndex(segment: String): Pair<String, Int?> {
        val start = segment.indexOf('[')
        if (start < 0) return segment to null
        val end = segment.indexOf(']', start)
        if (end < 0) return segment to null
        val key = segment.substring(0, start)
        val index = segment.substring(start + 1, end).toIntOrNull()
        return key to index
    }

    private fun indexOf(element: JsonElement, index: Int): JsonElement? {
        if (!element.isJsonArray) return null
        val array = element.asJsonArray
        val resolved = if (index < 0) array.size() + index else index
        if (resolved < 0 || resolved >= array.size()) return null
        return array.get(resolved)
    }
}
