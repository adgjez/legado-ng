package io.legado.app.help.ai

/**
 * 媒体端点与鉴权收口。
 *
 * 路径一律允许用户在 Provider 设置里覆盖，未填时回落到协议族默认值，
 * 这样既能开箱即用，也能在不改代码的前提下兼容自建中转。
 */
internal object AiMediaEndpoints {

    internal fun imagePath(setting: AiProviderSetting, protocol: AiMediaProtocol): String {
        val raw = setting.imageGenerationsPath.ifBlank { protocol.defaultImagePath }
        return resolve(setting, raw, setting.modelFor(AiMediaKind.IMAGE))
    }

    internal fun imageEditsPath(setting: AiProviderSetting): String {
        val raw = setting.imageEditsPath.ifBlank { "/images/edits" }
        return resolve(setting, raw, setting.modelFor(AiMediaKind.IMAGE))
    }

    internal fun videoPath(setting: AiProviderSetting, protocol: AiMediaProtocol): String {
        val raw = setting.videoGenerationsPath.ifBlank { protocol.defaultVideoPath }
        return resolve(setting, raw, setting.modelFor(AiMediaKind.VIDEO))
    }

    /**
     * 任务查询地址。
     *
     * 万相的图片同样是异步任务，所以查询地址也要感知 [kind]，
     * 否则用户自定义路径里的 {model} 会被填成另一类模型。
     */
    internal fun taskUrl(
        setting: AiProviderSetting,
        protocol: AiMediaProtocol,
        taskId: String,
        kind: AiMediaKind = AiMediaKind.VIDEO
    ): String {
        val raw = setting.videoTaskPath.ifBlank { protocol.defaultTaskPath }
        return resolve(setting, raw, setting.modelFor(kind))
            .replace("{id}", taskId)
            .replace("{name}", taskId)
    }

    internal fun mediaHeaders(setting: AiProviderSetting, protocol: AiMediaProtocol): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/json"
        if (setting.apiKey.isNotBlank()) {
            if (protocol == AiMediaProtocol.GOOGLE) {
                headers["x-goog-api-key"] = setting.apiKey
            } else {
                headers["Authorization"] = "Bearer ${setting.apiKey}"
            }
        }
        // 万相系列必须显式声明异步，否则报 "does not support synchronous calls"
        if (protocol == AiMediaProtocol.DASHSCOPE) {
            headers["X-DashScope-Async"] = "enable"
        }
        return headers
    }

    /**
     * [model] 必须显式传入：Google 的路径形如 /models/{model}:predict，
     * 若沿用 chat 模型的 [AiProviderSetting.model]，生成图片时会请求到错误的模型。
     */
    private fun resolve(setting: AiProviderSetting, raw: String, model: String): String {
        val withModel = raw.replace("{model}", model)
        return buildAiApiEndpoint(setting.baseUrl, withModel)
    }
}
