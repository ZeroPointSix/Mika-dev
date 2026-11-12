package com.wangzan101.mika.core.model

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GeminiDecisionModelTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun model() = GeminiDecisionModel(
        server.url("/v1beta").toString().trimEnd('/'), "gk", http = OkHttpClient(), headers = mapOf("User-Agent" to "Mika/test"),
    )
    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun JsonObject.obj(k: String) = this[k]!!.jsonObject
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    private val noul = DecisionQuestion.Noul("Is it done?", "goal reached", "not yet")
    private val choice = DecisionQuestion.Choice("Which control?", mapOf("c1" to "Search", "c2" to "", "DONE" to "Goal reached"))
    private val score = DecisionQuestion.Score("How risky?", listOf("low", "mid", "high"))
    private val all = mapOf("done" to noul, "pick" to choice, "risk" to score)

    private val reply = """{"answers":{
        "done":{"type":"noul","noul":0.97,"confidence":0.97},
        "pick":{"type":"choice","choice":"c1","probabilities":{"c1":0.93,"c2":0.05,"DONE":0.02},"confidence":0.93},
        "risk":{"type":"score","score":1.21,"legend":{"0":"low","1":"mid","2":"high"},"probabilities":{"0":0.04,"1":0.75,"2":0.21},"confidence":0.75}
    },"usage":{"total_input_tokens":412,"total_output_tokens":0,"total_tokens":412}}"""

    @Test fun requestShapeForAllQuestionTypes() = runTest {
        server.enqueue(ok(reply))
        model().decide("screen: ...", all)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1beta/decisions", req.path)
        assertEquals("gk", req.getHeader("x-goog-api-key"))
        assertEquals("Mika/test", req.getHeader("User-Agent"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("gemini-decision-1", body.str("model"))
        assertEquals("screen: ...", body.str("input"))
        val qs = body.obj("questions")
        assertEquals(listOf("done", "pick", "risk"), qs.keys.toList())
        assertEquals(
            Json.parseToJsonElement("""{"type":"noul","instructions":"Is it done?","criteria":{"true_description":"goal reached","false_description":"not yet"}}"""),
            qs["done"],
        )
        assertEquals(
            Json.parseToJsonElement("""{"type":"choice","instructions":"Which control?","criteria":{"c1":"Search","c2":"","DONE":"Goal reached"}}"""),
            qs["pick"],
        )
        assertEquals(
            Json.parseToJsonElement("""{"type":"score","instructions":"How risky?","criteria":["low","mid","high"]}"""),
            qs["risk"],
        )
    }

    @Test fun noulWithoutCriteriaOmitsIt() = runTest {
        server.enqueue(ok("""{"answers":{"q":{"type":"noul","noul":0.2,"confidence":0.8}}}"""))
        val r = model().decide("x", mapOf("q" to DecisionQuestion.Noul("Yes?")))
        assertFalse(Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.obj("questions").obj("q").containsKey("criteria"))
        assertEquals(DecisionAnswer.Noul(0.2, 0.8), r.answers["q"])
        assertNull(r.usage)
    }

    @Test fun parsesAllAnswerTypes() = runTest {
        server.enqueue(ok(reply))
        val r = model().decide("x", all)
        assertEquals(listOf("done", "pick", "risk"), r.answers.keys.toList())
        assertEquals(DecisionAnswer.Noul(0.97, 0.97), r.answers["done"])
        assertEquals(DecisionAnswer.Choice("c1", mapOf("c1" to 0.93, "c2" to 0.05, "DONE" to 0.02), 0.93), r.answers["pick"])
        assertEquals(DecisionAnswer.Score(1, listOf(0.04, 0.75, 0.21), 0.75), r.answers["risk"])
        assertEquals(Usage(412, 0), r.usage)
    }

    @Test fun fillsMissingProbabilitiesAndConfidence() = runTest {
        server.enqueue(ok("""{"answers":{
            "pick":{"type":"choice","choice":"c2","probabilities":{"c2":0.6}},
            "risk":{"type":"score","score":2.4}
        }}"""))
        val r = model().decide("x", mapOf("pick" to choice, "risk" to score))
        assertEquals(DecisionAnswer.Choice("c2", mapOf("c1" to 0.0, "c2" to 0.6, "DONE" to 0.0), 0.6), r.answers["pick"])
        assertEquals(2, (r.answers["risk"] as DecisionAnswer.Score).level)
    }

    @Test fun rejectsBadNamesLocally() = runTest {
        fun bad(qs: Map<String, DecisionQuestion>) = try {
            model().payload("x", qs); fail("should reject $qs")
        } catch (e: IllegalArgumentException) { Unit }
        bad(emptyMap())
        bad(mapOf("has space" to noul))
        bad(mapOf("a".repeat(65) to noul))
        bad(mapOf("q" to DecisionQuestion.Choice("i", mapOf("ok" to "", "bad.name" to ""))))
        bad(mapOf("q" to DecisionQuestion.Choice("i", mapOf("only" to ""))))
        bad(mapOf("q" to DecisionQuestion.Choice("i", (0..255).associate { "o$it" to "" })))
        bad(mapOf("q" to DecisionQuestion.Score("i", listOf("one"))))
        try { model().decide("x", mapOf("bad name" to noul)); fail() } catch (e: IllegalArgumentException) { }
        assertEquals(0, server.requestCount)
    }

    private suspend fun malformed(body: String, qs: Map<String, DecisionQuestion> = all) {
        server.enqueue(ok(body))
        try { model().decide("x", qs); fail("should reject $body") } catch (e: ModelError.Server) {
            assertEquals(GeminiDecisionModel.MALFORMED_STATUS, e.status)
        }
    }

    @Test fun malformedRepliesAreServerErrors() = runTest {
        malformed("""{"usage":{}}""")
        malformed("""{"answers":{"done":{"type":"noul","noul":0.9}}}""") // 缺题
        malformed("""{"answers":{"q":{"type":"choice","choice":"x","probabilities":{"x":1}}}}""", mapOf("q" to noul)) // 类型不符
        malformed("""{"answers":{"q":{"type":"choice","choice":"zz","probabilities":{"zz":1}}}}""", mapOf("q" to choice)) // 不是选项
        malformed("""{"answers":{"q":{"type":"choice","choice":"c1","probabilities":{"c1":0.5,"zz":0.5}}}}""", mapOf("q" to choice))
        malformed("""{"answers":{"q":{"type":"noul","noul":1.7}}}""", mapOf("q" to noul)) // 概率越界
        malformed("""{"answers":{"q":{"type":"noul","noul":"0.5"}}}""", mapOf("q" to noul))
        malformed("""{"answers":{"q":{"type":"score","probabilities":{"5":1}}}}""", mapOf("q" to score)) // 没这一级
        malformed("""{"answers":{"q":{"type":"score"}}}""", mapOf("q" to score))
    }

    @Test fun nonJsonIsBadResponse() = runTest {
        server.enqueue(MockResponse().setBody("<html>hi</html>"))
        try { model().decide("x", mapOf("q" to noul)); fail() } catch (e: ModelError) { assertTrue(e is ModelError.BadResponse) }
    }

    private suspend fun errorOf(code: Int, body: String, vararg headers: Pair<String, String>): ModelError {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body).apply { headers.forEach { setHeader(it.first, it.second) } })
        return try { model().decide("x", mapOf("q" to noul)); fail(); throw AssertionError() } catch (e: ModelError) { e }
    }
    private fun err(code: String, msg: String) = """{"error":{"message":"$msg","code":"$code"}}"""

    @Test fun mapsErrors() = runTest {
        assertTrue(errorOf(400, err("safety", "Input was blocked.")) is ModelError.ContentRefused)
        assertTrue(errorOf(400, err("invalid_request", "Unknown parameter 'state'.")) is ModelError.BadResponse)
        assertTrue(errorOf(400, err("invalid_request", "The input token count (300000) exceeds the maximum number of tokens allowed (262144).")) is ModelError.ContextTooLong)
        assertTrue(errorOf(401, err("unauthenticated", "no")) is ModelError.Auth)
        // 谷歌对无效 Key 回 400(实测原样)
        assertTrue(errorOf(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""") is ModelError.Auth)
        assertTrue(errorOf(403, err("permission_denied", "no")) is ModelError.Auth)
        assertEquals(3000L, (errorOf(429, err("rate_limited", "slow down"), "Retry-After" to "3") as ModelError.RateLimited).retryAfterMs)
        assertTrue(errorOf(404, err("not_found", "models/gemini-decision-1 not found")) is ModelError.ModelNotFound)
        assertEquals(503, (errorOf(503, err("unavailable", "busy")) as ModelError.Server).status)
    }

    @Test fun networkFailure() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        try { model().decide("x", mapOf("q" to noul)); fail() } catch (e: ModelError) { assertTrue(e is ModelError.Network) }
    }

    @Test fun factory() {
        assertNull(DecisionModels.create("jev", null, "k", "m", OkHttpClient()))
        val m = DecisionModels.create(DecisionModels.GEMINI, null, "k", "", OkHttpClient())!!
        assertEquals("gemini-decision-1", m.name)
        assertEquals(262_144, m.contextTokens)
        assertEquals("gemini-decision-1", DecisionModels.defaultModel(DecisionModels.GEMINI))
        assertEquals("https://generativelanguage.googleapis.com/v1beta", DecisionModels.geminiBase(null))
        assertEquals("https://generativelanguage.googleapis.com/v1beta", DecisionModels.geminiBase("https://generativelanguage.googleapis.com/"))
        assertEquals("https://example.com/v1alpha", DecisionModels.geminiBase("https://example.com/v1alpha/"))
        assertEquals("https://example.com/v1", DecisionModels.geminiBase("https://example.com/v1"))
    }
}
