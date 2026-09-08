package io.legado.app.help.ai

import android.util.Base64
import io.legado.app.help.http.await
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import okhttp3.Request
import splitties.init.appCtx

/**
 * 把 [AiMediaReference] 归一化为字节。
 *
 * 角色垫图（AI 漫画）与首尾帧（AI 漫剧）都要走这里：来源既可能是用户相册里的本地 uri，
 * 也可能是上一轮生成留下的远程 URL，还可能是内存里的 base64。
 */
internal object AiMediaReferenceResolver {

    internal class AiMediaReferencePayload(
        val bytes: ByteArray,
        val mimeType: String,
        val fileName: String
    )

    internal suspend fun resolve(
        reference: AiMediaReference,
        setting: AiProviderSetting
    ): AiMediaReferencePayload? {
        val source = reference.source.trim()
        if (source.isBlank()) return null
        return when {
            reference.isDataUri -> decodeDataUri(source, reference.mimeType, reference.label)
            reference.isRemoteUrl -> download(source, setting, reference.mimeType, reference.label)
            else -> readLocal(source, reference.mimeType, reference.label)
        }
    }

    internal fun toDataUri(bytes: ByteArray, mimeType: String): String {
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:$mimeType;base64,$encoded"
    }

    private fun decodeDataUri(
        source: String,
        fallbackMime: String?,
        label: String?
    ): AiMediaReferencePayload? {
        val commaIndex = source.indexOf(',')
        if (commaIndex <= 0) return null
        val meta = source.substring(0, commaIndex)
        val payload = source.substring(commaIndex + 1)
        val mimeType = meta.substringAfter("data:", "")
            .substringBefore(';')
            .trim()
            .takeIf { it.isNotBlank() }
            ?: fallbackMime
            ?: "image/png"
        if (!meta.contains("base64", ignoreCase = true)) return null
        val bytes = Base64.decode(payload, Base64.DEFAULT)
        if (bytes.isEmpty()) return null
        return AiMediaReferencePayload(bytes, mimeType, fileNameFor(mimeType, label))
    }

    private suspend fun download(
        url: String,
        setting: AiProviderSetting,
        fallbackMime: String?,
        label: String?
    ): AiMediaReferencePayload? = withContext(IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .apply {
                    if (setting.apiKey.isNotBlank()) {
                        addHeader("Authorization", "Bearer ${setting.apiKey}")
                    }
                }
                .get()
                .build()
            aiHttpClient(60).newCall(request).await().use { response ->
                if (!response.isSuccessful) return@withContext null
                val mimeType = response.header("Content-Type")
                    ?.substringBefore(';')
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: fallbackMime
                    ?: "image/png"
                val bytes = response.body.bytes()
                if (bytes.isEmpty()) return@withContext null
                AiMediaReferencePayload(bytes, mimeType, fileNameFor(mimeType, label))
            }
        }.getOrNull()
    }

    private suspend fun readLocal(
        uri: String,
        fallbackMime: String?,
        label: String?
    ): AiMediaReferencePayload? = withContext(IO) {
        runCatching {
            val resolver = appCtx.contentResolver
            val mimeType = resolver.getType(android.net.Uri.parse(uri))
                ?.takeIf { it.isNotBlank() }
                ?: fallbackMime
                ?: "image/png"
            resolver.openInputStream(android.net.Uri.parse(uri))?.use { stream ->
                val bytes = stream.readBytes()
                if (bytes.isEmpty()) return@withContext null
                AiMediaReferencePayload(bytes, mimeType, fileNameFor(mimeType, label))
            }
        }.getOrNull()
    }

    private fun fileNameFor(mimeType: String, label: String?): String {
        val base = label?.trim()?.takeIf { it.isNotBlank() } ?: "reference"
        val safe = base.replace(Regex("[^A-Za-z0-9_\\-一-龥]"), "_").take(48)
        val ext = when {
            mimeType.contains("jpeg", ignoreCase = true) || mimeType.contains("jpg", ignoreCase = true) -> "jpg"
            mimeType.contains("webp", ignoreCase = true) -> "webp"
            mimeType.contains("mp4", ignoreCase = true) -> "mp4"
            else -> "png"
        }
        return "$safe.$ext"
    }
}
