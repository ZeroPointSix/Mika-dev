package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunMetrics
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopDelegateTest {
    private var seq = 0
    private fun call(name: String, args: String = "{}") = Part.ToolCall("d${++seq}", name, args)
    private fun reply(vararg parts: Part) = ModelReply(parts.toList(), StopReason.ToolUse, null)
    private fun finish() = com.wangzan101.mika.core.testing.answerReply("done")
    private val pages = mapOf("home" to listOf("消息", "设置"), "settings" to listOf("WLAN", "蓝牙"))
    private val on = SpeedFlags(delegateSteps = true)
    private fun lastResult(m: FakeModel, i: Int) = m.requests[i].withoutRuntime().last().parts.filterIsInstance<Part.ToolResult>().single()
        .let { Json.parseToJsonElement(it.content).jsonObject }

    @Test fun offeredOnlyWithFlagAndModelAndNotToChildRuns() = runTest {
        fun loop(m: FakeModel, flags: SpeedFlags, decision: FakeDecision?) = AgentLoop(m, toolDispatcher(FakeScreen()), SystemPrompt.identity(), flags = flags, decision = decision)
        val off = FakeModel(reply(call("phone_observe")), finish())
        loop(off, SpeedFlags.OFF, FakeDecision()).run("t")
        assertTrue(off.offeredTools.none { DELEGATE_STEPS_TOOL in it })
        val noModel = FakeModel(reply(call("phone_observe")), finish())
        loop(noModel, on, null).run("t")
        assertTrue(noModel.offeredTools.none { DELEGATE_STEPS_TOOL in it })
        // 和一次多步一样跟着有屏幕的组:手机一组展开后才有;系统提示词不因它改变
        val yes = FakeModel(reply(call("phone_observe")), finish())
        loop(yes, on, FakeDecision()).run("t")
        assertTrue(DELEGATE_STEPS_TOOL !in yes.offeredTools.first())
        assertTrue(DELEGATE_STEPS_TOOL in yes.offeredTools[1])
        assertEquals(off.system, yes.system)
        // 子任务不给
        val child = FakeModel(reply(call("phone_observe")), finish())
        loop(child, on, FakeDecision()).run(RunSpec(RunId("c"), "t", "th", provenance = RunProvenance(RunOrigin.Child, Attendance.Unattended)), RunInbox())
        assertTrue(child.offeredTools.none { DELEGATE_STEPS_TOOL in it })
    }

    @Test fun delegationIsOneStepAndHandsBackScreenAndRecords() = runTest {
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }
        val s = FlowScreen("home", pages, mapOf("设置" to "settings"))
        val decision = FakeDecision(FakeDecision.Pick("Tap button \"设置\"", 0.95), FakeDecision.Pick("DONE", 0.99))
        val m = FakeModel(reply(call("phone_observe")), reply(call(DELEGATE_STEPS_TOOL, """{"goal":"settings page is open","texts":["secret-ish"]}""")), finish())
        val r = AgentLoop(m, toolDispatcher(s), "BASE", maxSteps = 3, sink = sink, flags = on, decision = decision).run("open settings")
        assertTrue(r is TaskState.Succeeded)
        val result = lastResult(m, 2)
        assertEquals("done", result["status"]!!.jsonPrimitive.content)
        assertTrue(result["screen"]!!.toString().contains("WLAN"))
        assertEquals(listOf("tap 设置"), s.calls)
        val replies = events.filterIsInstance<RunEvent.ModelReply>()
        assertEquals(2, replies.count { it.role == ModelCaller.ROLE_DECISION })
        // 主模型那一次回复的参数里要输入的字打了码
        val main = replies.single { it.tool == DELEGATE_STEPS_TOOL }
        assertTrue("secret-ish" !in main.argsJson!!)
        // 小结:决策模型的调用另算,不算主循环的请求;子步算操作,不和外层重复
        val metrics = RunMetrics.of(events)!!
        assertEquals(2, metrics.decisionCalls)
        assertEquals(3, metrics.modelCalls)
        assertEquals(3, metrics.steps)
    }

    @Test fun persistentDecisionErrorDisablesDelegationForTheRun() = runTest {
        val s = FlowScreen("home", pages, mapOf("设置" to "settings"))
        val decision = FakeDecision(ModelError.Auth())
        val args = """{"goal":"settings page is open"}"""
        val m = FakeModel(reply(call("phone_observe")), reply(call(DELEGATE_STEPS_TOOL, args)), reply(call(DELEGATE_STEPS_TOOL, args)), finish())
        AgentLoop(m, toolDispatcher(s), "BASE", flags = on, decision = decision).run("t")
        assertEquals("decision_error", lastResult(m, 2)["reason"]!!.jsonPrimitive.content)
        assertEquals("decision_unavailable", lastResult(m, 3)["reason"]!!.jsonPrimitive.content)
        assertEquals(1, decision.inputs.size)
        // 工具照常提供,前缀不变
        assertTrue(DELEGATE_STEPS_TOOL in m.offeredTools[3])
        assertTrue(s.calls.isEmpty())
    }
}
