package com.wangzan101.mika.core.model

import okhttp3.OkHttpClient

/**
 * 按服务商种类建 [DecisionModel],App 只认这里,不依赖具体适配器。以后加别家(如 TypeSafe 的 Jev)只在这里多认一个 [kind]。
 */
object DecisionModels {
    const val GEMINI = "gemini"

    /** 认得的种类。 */
    val kinds: List<String> = listOf(GEMINI)

    /** 这一种的默认模型名;认不出为 null。 */
    fun defaultModel(kind: String): String? = when (kind) {
        GEMINI -> GeminiDecisionModel.DEFAULT_MODEL
        else -> null
    }

    /**
     * 认不出 [kind] 为 null。[baseUrl] 为空用这一家的默认地址;Gemini 的地址没带版本号(比如对话服务商的
     * `https://generativelanguage.googleapis.com`)时补上 `/v1beta`。[contextTokens] 为 null 用模型的默认上限。
     */
    fun create(
        kind: String,
        baseUrl: String?,
        apiKey: String,
        model: String,
        http: OkHttpClient,
        headers: Map<String, String> = emptyMap(),
        contextTokens: Int? = null,
    ): DecisionModel? = when (kind) {
        GEMINI -> GeminiDecisionModel(
            baseUrl = geminiBase(baseUrl),
            apiKey = apiKey,
            model = model.ifBlank { GeminiDecisionModel.DEFAULT_MODEL },
            http = http,
            headers = headers,
            contextTokens = contextTokens ?: GeminiDecisionModel.DEFAULT_CONTEXT_TOKENS,
        )
        else -> null
    }

    private val VERSIONED = Regex("""/v\d+(?:alpha|beta)?\d*$""")

    internal fun geminiBase(baseUrl: String?): String {
        val base = baseUrl?.trim()?.trimEnd('/').orEmpty()
        return when {
            base.isEmpty() -> GeminiDecisionModel.DEFAULT_BASE_URL
            VERSIONED.containsMatchIn(base) -> base
            else -> "$base/v1beta"
        }
    }
}
