package com.wangzan101.mika.core.usage

import com.wangzan101.mika.core.model.DecisionModels
import com.wangzan101.mika.core.model.DecisionQuestion
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.Usage
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class MeteredDecisionModelTest {
    private lateinit var server: MockWebServer
    private val source = UsageSource("p1", "Google", "gemini-decision-1", UsagePurpose.Decision)
    private val recorded = mutableListOf<Triple<UsageSource, Usage?, Int>>()
    private val sink = object : UsageSink {
        override fun record(source: UsageSource, usage: Usage?) = record(source, usage, 1)
        override fun record(source: UsageSource, usage: Usage?, httpAttempts: Int) { recorded += Triple(source, usage, httpAttempts) }
    }
    private val q = mapOf("q" to DecisionQuestion.Noul("Yes?"))

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun model() = DecisionModels.create(DecisionModels.GEMINI, server.url("/v1beta").toString(), "k", "", OkHttpClient())!!
        .metered(source, sink)

    @Test fun recordsUsageAndHttpAttempts() = runTest {
        server.enqueue(MockResponse().setBody("""{"answers":{"q":{"type":"noul","noul":0.9,"confidence":0.9}},"usage":{"total_input_tokens":300,"total_output_tokens":0}}"""))
        val tally = RunUsageTally()
        withContext(tally) { model().decide("x", q) }
        assertEquals(listOf(Triple(source, Usage(300, 0), 1)), recorded)
        assertEquals(300L, tally.totalTokens())
        assertEquals("gemini-decision-1", model().name)
        assertEquals(262_144, model().contextTokens)
    }

    @Test fun failureRecordsAttemptWithoutUsage() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        try { model().decide("x", q); fail() } catch (e: ModelError.Server) { }
        assertEquals(listOf(Triple(source, null as Usage?, 1)), recorded)
    }

    @Test fun exhaustedBudgetRejectsBeforeSending() = runTest {
        val tally = RunUsageTally(tokenBudget = 5).apply { add(Usage(5, 0), 1) }
        try { withContext(tally) { model().decide("x", q) }; fail() } catch (e: ModelError.TaskBudgetExceeded) { }
        assertEquals(0, server.requestCount)
        assertEquals(emptyList<Any>(), recorded)
    }

    @Test fun purposeKeyRoundTrips() {
        assertEquals(UsagePurpose.Decision, UsagePurpose.of("decision"))
    }
}
