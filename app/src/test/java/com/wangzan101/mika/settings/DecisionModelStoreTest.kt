package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.DecisionModels
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.usage.UsagePurpose
import com.wangzan101.mika.core.usage.UsageSink
import com.wangzan101.mika.core.usage.UsageSource
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DecisionModelStoreTest {
    /** 把明文倒过来当「密文」:存下的不是明文,解开又对得上。 */
    private val reversing = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(data: ByteArray) = data.reversedArray()
    }
    private val server = MockWebServer()
    private fun store(prefs: MemoryPrefs = MemoryPrefs()) = DecisionModelStore(prefs, reversing, { OkHttpClient() }, "Mika/test")

    @After fun stop() = server.shutdown()

    @Test fun freshInstallIsNotConfigured() {
        val config = store().get()
        assertEquals(DecisionModels.GEMINI, config.kind)
        assertFalse(config.configured)
        assertEquals("gemini-decision-1", config.modelName)
        assertNull(store().build())
        assertNull(store().modelFor { _, _ -> fail() })
    }

    @Test fun keyIsStoredEncryptedAndReadBack() {
        val prefs = MemoryPrefs()
        store(prefs).set(DecisionModelConfig(apiKey = " secret-key ", baseUrl = " https://example.org ", model = " m1 "))
        // 存的是经 KeyCipher 加密后的,不是明文(也不是明文的 Base64)
        val plainB64 = java.util.Base64.getEncoder().encodeToString("secret-key".toByteArray())
        assertFalse(prefs.all.values.any { it.toString().contains("secret-key") || it.toString() == plainB64 })
        val config = store(prefs).get()
        assertEquals("secret-key", config.apiKey)
        assertEquals("https://example.org", config.baseUrl)
        assertEquals("m1", config.modelName)
        assertTrue(config.configured)
        assertNotNull(store(prefs).build())
    }

    @Test fun emptyKeyRemovesTheStoredOne() {
        val prefs = MemoryPrefs()
        store(prefs).set(DecisionModelConfig(apiKey = "k"))
        store(prefs).set(DecisionModelConfig(apiKey = ""))
        assertFalse(store(prefs).get().configured)
        assertNull(store(prefs).build())
    }

    @Test fun unknownKindReadsAsGemini() {
        val prefs = MemoryPrefs()
        prefs.edit().putString("kind", "someday").commit()
        assertEquals(DecisionModels.GEMINI, store(prefs).get().kind)
    }

    /** 任务用的模型带着计量:每次调用记一笔「决策」用量,来源是这一种决策模型而不是哪家对话服务商。 */
    @Test fun taskModelIsMeteredAsDecision() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"answers":{"ok":{"type":"noul","noul":0.99,"confidence":0.99}},"usage":{"total_input_tokens":20,"total_output_tokens":0,"total_tokens":20}}""",
        ))
        val prefs = MemoryPrefs()
        store(prefs).set(DecisionModelConfig(apiKey = "k", baseUrl = server.url("/v1beta").toString()))
        val recorded = mutableListOf<Pair<UsageSource, Usage?>>()
        val model = store(prefs).modelFor(UsageSink { s, u -> recorded += s to u })!!
        model.decide("x", mapOf("ok" to com.wangzan101.mika.core.model.DecisionQuestion.Noul("?")))
        assertEquals(1, recorded.size)
        assertEquals(UsagePurpose.Decision, recorded[0].first.purpose)
        assertEquals("decision-gemini", recorded[0].first.providerId)
        assertEquals("Gemini Decisions", recorded[0].first.providerName)
        assertEquals("Gemini Decisions", DecisionModelStore.usageProviders["decision-gemini"])
        assertEquals("gemini-decision-1", recorded[0].first.model)
        assertEquals(Usage(20, 0), recorded[0].second)
        val req = server.takeRequest()
        assertEquals("/v1beta/decisions", req.path)
        assertEquals("k", req.getHeader("x-goog-api-key"))
        assertEquals("Mika/test", req.getHeader("User-Agent"))
    }

    /** 测试用框里还没存的值,服务商的报错原样抛出。 */
    @Test fun testUsesUnsavedValuesAndSurfacesErrors() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":400,"message":"API key not valid","status":"INVALID_ARGUMENT"}}"""))
        val s = store()
        try {
            s.test(DecisionModelConfig(apiKey = "bad", baseUrl = server.url("/v1beta").toString()))
            fail()
        } catch (e: ModelError.Auth) {
        }
        assertEquals("bad", server.takeRequest().getHeader("x-goog-api-key"))
        assertNull(s.build())
    }
}
