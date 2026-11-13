package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.usage.ModelHttpAttempts
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Gemini Decisions API(`POST {baseUrl}/decisions`)。[baseUrl] 含版本号,如 `https://generativelanguage.googleapis.com/v1beta`。
 * - 问题名、选项名在本地按 `[A-Za-z0-9_-]{1,64}` 校验,不合就抛 IllegalArgumentException,不发请求。
 * - 回复逐题核对:缺题、类型不符、选了不存在的选项、概率不在 0–1 都抛 [ModelError.Server](502),不交回形状不对的答案。
 * - 打分题的 [DecisionAnswer.Score.level] 取概率最高的那一级。
 * - 不打推理请求的追踪标记(那是主循环对话模型的),请求数仍记到计量里(见 [ModelHttpAttempts])。
 */
class GeminiDecisionModel(
    baseUrl: String = DEFAULT_BASE_URL,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val http: OkHttpClient,
    /** 每个请求都带的头(见 [ClientHeaders]);Key 的头由适配器自己加。 */
    private val headers: Map<String, String> = emptyMap(),
    override val contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
) : DecisionModel {
    override val name: String get() = model
    private val endpoint = baseUrl.trimEnd('/') + "/decisions"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun decide(input: String, questions: Map<String, DecisionQuestion>): DecisionReply {
        val payload = payload(input, questions)
        val req = Request.Builder().url(endpoint)
            .withHeaders(headers)
            .header("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        currentCoroutineContext()[ModelHttpAttempts]?.start()
        val (status, retryAfter, body) = HttpSupport.execute(http, req)
        if (status !in 200..299) throw mapError(status, retryAfter, body)
        return parse(body, questions)
    }

    internal fun payload(input: String, questions: Map<String, DecisionQuestion>): JsonObject {
        require(questions.isNotEmpty()) { "Decision request needs at least one question" }
        return buildJsonObject {
            put("model", model)
            put("input", input)
            putJsonObject("questions") {
                for ((name, q) in questions) {
                    requireName("question", name)
                    putJsonObject(name) {
                        when (q) {
                            is DecisionQuestion.Noul -> {
                                put("type", "noul")
                                put("instructions", q.instructions)
                                if (q.trueDescription != null || q.falseDescription != null) putJsonObject("criteria") {
                                    put("true_description", q.trueDescription.orEmpty())
                                    put("false_description", q.falseDescription.orEmpty())
                                }
                            }
                            is DecisionQuestion.Choice -> {
                                require(q.options.size in 2..MAX_OPTIONS) { "Question '$name' needs 2–$MAX_OPTIONS options, got ${q.options.size}" }
                                q.options.keys.forEach { requireName("option of '$name'", it) }
                                put("type", "choice")
                                put("instructions", q.instructions)
                                putJsonObject("criteria") { q.options.forEach { (k, v) -> put(k, v) } }
                            }
                            is DecisionQuestion.Score -> {
                                require(q.levels.size in 2..MAX_OPTIONS) { "Question '$name' needs 2–$MAX_OPTIONS levels, got ${q.levels.size}" }
                                put("type", "score")
                                put("instructions", q.instructions)
                                putJsonArray("criteria") { q.levels.forEach { add(JsonPrimitive(it)) } }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requireName(what: String, name: String) =
        require(NAME.matches(name)) { "Invalid $what name '$name': must match [A-Za-z0-9_-]{1,64}" }

    private fun mapError(status: Int, retryAfter: String?, body: String): ModelError {
        val err = (runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject)?.get("error") as? JsonObject
        val code = (err?.get("code") as? JsonPrimitive)?.contentOrNull
        if (code == "safety") return ModelError.ContentRefused()
        // 谷歌对无效 Key 回的是 HTTP 400(同 ModelCatalog 的认法)
        if (status == 400 && ("API_KEY_INVALID" in body || "API key not valid" in body)) return ModelError.Auth()
        return ModelHttp.geminiError(status, retryAfter, body, model)
    }

    private fun parse(body: String, questions: Map<String, DecisionQuestion>): DecisionReply {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: throw ModelError.BadResponse(body.take(200))
        val answers = root["answers"] as? JsonObject ?: malformed("no answers")
        val parsed = LinkedHashMap<String, DecisionAnswer>()
        for ((name, q) in questions) {
            val a = answers[name] as? JsonObject ?: malformed("missing answer '$name'")
            parsed[name] = answer(name, q, a)
        }
        return DecisionReply(parsed, usage(root["usage"] as? JsonObject))
    }

    private fun answer(name: String, q: DecisionQuestion, a: JsonObject): DecisionAnswer {
        val type = (a["type"] as? JsonPrimitive)?.contentOrNull
        val want = when (q) {
            is DecisionQuestion.Noul -> "noul"
            is DecisionQuestion.Choice -> "choice"
            is DecisionQuestion.Score -> "score"
        }
        if (type != want) malformed("answer '$name' is '$type', asked '$want'")
        return when (q) {
            is DecisionQuestion.Noul -> {
                val p = prob(a["noul"]) ?: malformed("answer '$name' has no noul")
                DecisionAnswer.Noul(p, prob(a["confidence"]) ?: maxOf(p, 1 - p))
            }
            is DecisionQuestion.Choice -> {
                val choice = (a["choice"] as? JsonPrimitive)?.contentOrNull
                if (choice == null || choice !in q.options) malformed("answer '$name' chose '$choice', not an option")
                val given = probabilities(name, a)
                given.keys.firstOrNull { it !in q.options }?.let { malformed("answer '$name' has unknown option '$it'") }
                val probs = q.options.keys.associateWith { given[it] ?: 0.0 }
                DecisionAnswer.Choice(choice, probs, prob(a["confidence"]) ?: probs.getValue(choice))
            }
            is DecisionQuestion.Score -> {
                val given = probabilities(name, a)
                val probs = MutableList(q.levels.size) { 0.0 }
                for ((k, v) in given) {
                    val i = k.toIntOrNull()?.takeIf { it in q.levels.indices } ?: malformed("answer '$name' has unknown level '$k'")
                    probs[i] = v
                }
                val level = if (given.isNotEmpty()) probs.indices.maxBy { probs[it] }
                else (a["score"] as? JsonPrimitive)?.doubleOrNull?.let { Math.round(it).toInt() }?.takeIf { it in q.levels.indices }
                    ?: malformed("answer '$name' has no score")
                DecisionAnswer.Score(level, probs, prob(a["confidence"]) ?: probs[level])
            }
        }
    }

    private fun probabilities(name: String, a: JsonObject): Map<String, Double> {
        val obj = a["probabilities"] ?: return emptyMap()
        if (obj !is JsonObject) malformed("answer '$name' probabilities is not an object")
        return obj.mapValues { (k, v) -> prob(v) ?: malformed("answer '$name' probability of '$k' is not in 0–1") }
    }

    /** 0–1 之间的数;缺了或不在范围内为 null。 */
    private fun prob(e: JsonElement?): Double? =
        (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() && it in 0.0..1.0 }

    private fun usage(u: JsonObject?): Usage? {
        u ?: return null
        val input = (u["total_input_tokens"] as? JsonPrimitive)?.intOrNull ?: return null
        return Usage(input, (u["total_output_tokens"] as? JsonPrimitive)?.intOrNull ?: 0)
    }

    private fun malformed(detail: String): Nothing =
        throw ModelError.Server(MALFORMED_STATUS).apply { addSuppressed(IllegalStateException("Decision reply: $detail")) }

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        const val DEFAULT_MODEL = "gemini-decision-1"
        /** gemini-decision-1 的 inputTokenLimit。 */
        const val DEFAULT_CONTEXT_TOKENS = 262_144
        /** 回复读得出 JSON、内容却和提问对不上:当作服务端出错(上游给了坏回复),不是请求或 Key 的问题。 */
        const val MALFORMED_STATUS = 502
        private const val MAX_OPTIONS = 255
        private val NAME = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
