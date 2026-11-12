package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.model.ClientHeaders
import com.wangzan101.mika.core.model.DecisionModel
import com.wangzan101.mika.core.model.DecisionModels
import com.wangzan101.mika.core.model.DecisionQuestion
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.usage.UsagePurpose
import com.wangzan101.mika.core.usage.UsageSink
import com.wangzan101.mika.core.usage.UsageSource
import com.wangzan101.mika.core.usage.metered
import okhttp3.OkHttpClient
import java.util.Base64

/**
 * 决策模型的设置:种类([DecisionModels.kinds],现在只有 Gemini)、地址(空用这一家的默认)、Key、模型名(空用默认)。
 * 决策模型不是对话模型,不进 [com.wangzan101.mika.core.model.ModelRole],单独存。
 */
data class DecisionModelConfig(
    val kind: String = DecisionModels.GEMINI,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    /** 填了 Key、种类认得:任务里才会建。 */
    val configured: Boolean get() = apiKey.isNotBlank() && kind in DecisionModels.kinds

    /** 实际用的模型名。 */
    val modelName: String get() = model.trim().ifEmpty { DecisionModels.defaultModel(kind).orEmpty() }
}

/**
 * 决策模型设置的存取。Key 和服务商的 Key 一样经 [KeyCipher](Android 密钥库)加密后存;不写日志、不进诊断信息
 * (诊断只收集状态,不读这里;App 也不做系统备份)。地址、模型名明文存。
 */
class DecisionModelStore(
    private val prefs: SharedPreferences,
    private val cipher: KeyCipher = KeystoreKeyCipher(KEY_ALIAS),
    /** 和对话模型共用的连接(走设置 › 网络代理)。 */
    private val http: () -> OkHttpClient = { ProviderStore.modelHttp },
    private val userAgent: String = "Mika",
) {
    fun get(): DecisionModelConfig {
        val kind = prefs.getString(KIND, null)?.takeIf { it in DecisionModels.kinds } ?: DecisionModels.GEMINI
        return DecisionModelConfig(
            kind = kind,
            baseUrl = prefs.getString(BASE_URL, null).orEmpty(),
            apiKey = decrypt(prefs.getString(API_KEY, null)),
            model = prefs.getString(MODEL, null).orEmpty(),
        )
    }

    /** Key 为空就删掉存着的 Key(等于不用决策模型)。 */
    @Synchronized
    fun set(config: DecisionModelConfig) {
        val key = config.apiKey.trim()
        val edit = prefs.edit()
            .putString(KIND, config.kind)
            .putString(BASE_URL, config.baseUrl.trim())
            .putString(MODEL, config.model.trim())
        if (key.isEmpty()) edit.remove(API_KEY)
        else edit.putString(API_KEY, Base64.getEncoder().encodeToString(cipher.encrypt(key.toByteArray(Charsets.UTF_8))))
        check(edit.commit()) { "Cannot save decision model settings" }
    }

    /** 按 [config] 建决策模型(不计量);没填 Key 或种类认不出时为 null。 */
    fun build(config: DecisionModelConfig = get()): DecisionModel? {
        if (!config.configured) return null
        return DecisionModels.create(
            config.kind, config.baseUrl.trim().ifEmpty { null }, config.apiKey.trim(), config.modelName, http(),
            ClientHeaders.of(userAgent, null, ModelSession.fresh()),
        )
    }

    /** 任务用的:按存着的设置建,套上计量,用量记在「决策」名下、受任务预算约束。没配好为 null。 */
    fun modelFor(usage: UsageSink): DecisionModel? {
        val config = get()
        val model = build(config) ?: return null
        return model.metered(source(config), usage)
    }

    /**
     * 「测试」:按还没存的 [config] 发一次很小的真实请求(一道是否题)。不计量:这几个 token 不属于任何任务。
     * 失败抛 [com.wangzan101.mika.core.model.ModelError](由调用方转成说明);超时由调用方管。没填 Key 时抛 [IllegalArgumentException]。
     */
    suspend fun test(config: DecisionModelConfig) {
        val model = requireNotNull(build(config)) { "No API key" }
        model.decide(PROBE_INPUT, mapOf(PROBE_QUESTION to DecisionQuestion.Noul(PROBE_INSTRUCTIONS)))
    }

    private fun decrypt(encoded: String?): String = encoded?.let {
        runCatching { String(cipher.decrypt(Base64.getDecoder().decode(it)), Charsets.UTF_8) }.getOrDefault("")
    }.orEmpty()

    companion object {
        private const val PREFS = "decision_model"
        private const val KEY_ALIAS = "mika-decision-key"
        private const val KIND = "kind"
        private const val BASE_URL = "base_url"
        private const val API_KEY = "api_key_encrypted"
        private const val MODEL = "model"

        private const val PROBE_INPUT = "Connection test from Mika. The sky is blue."
        private const val PROBE_QUESTION = "ok"
        private const val PROBE_INSTRUCTIONS = "Is the sky in the input described as blue?"

        /**
         * 用量里决策模型那一行:服务商记成「这一种决策模型」(id `decision-<种类>`),不和同一家的对话服务商混在一起,
         * 也就没有每日预算。
         */
        internal fun source(config: DecisionModelConfig) =
            UsageSource(usageId(config.kind), usageName(config.kind), config.modelName, UsagePurpose.Decision)

        private fun usageId(kind: String) = "decision-$kind"

        /** 用量页上的名字:品牌名加 Decisions,和同一家的对话服务商分得开。 */
        private fun usageName(kind: String) = "${providerName(kind)} Decisions"

        /** 用量页当作「还在」的那几行(不标已删除):每一种决策模型一行。 */
        val usageProviders: Map<String, String> get() = DecisionModels.kinds.associate { usageId(it) to usageName(it) }

        /** 种类在界面上的名字:品牌名,不翻译。 */
        fun providerName(kind: String): String = when (kind) {
            DecisionModels.GEMINI -> "Gemini"
            else -> kind
        }

        fun get(context: Context) = DecisionModelStore(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
            userAgent = "Mika/${BuildConfig.VERSION_NAME}",
        )
    }
}
