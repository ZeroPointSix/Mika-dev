package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.DecisionAnswer
import com.wangzan101.mika.core.model.DecisionModel
import com.wangzan101.mika.core.model.DecisionQuestion
import com.wangzan101.mika.core.model.DecisionReply
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.PageDialog
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.Verification
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 几屏手机界面:点名字在 [links] 里的元素就换到那一屏;别的点了不变。 */
internal class FlowScreen(private var page: String, private val pages: Map<String, List<String>>, private val links: Map<String, String> = emptyMap()) : ScreenPort by FakeScreen() {
    val calls = mutableListOf<String>()
    private var reads = 0
    /** 名字以「输入」开头的元素是输入框。 */
    private fun elements() = pages.getValue(page).mapIndexed { i, name ->
        UiElement("@e${i + 1}", if (name.startsWith("输入")) "EditText" else "button", name, 540, 100 + i * 200,
            listOf(0, 40 + i * 80, 999, 100 + i * 80), null, true, name.startsWith("输入"), false)
    }
    private fun snap(id: String) = ScreenSnapshot(id, "com.x", elements(), 1080, 2400)
    override suspend fun snapshot(): ScreenSnapshot = snap("p${++reads}")
    override suspend fun peek(): ScreenSnapshot = snap("peek")
    fun go(to: String) { page = to }
    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
        val name = elements().first { it.ref == ref }.name
        calls += "tap $name"
        val to = links[name] ?: return ActionResult.Ok(changed = false)
        page = to
        return ActionResult.Ok(changed = true)
    }
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult {
        calls += "type $text"; return ActionResult.Ok(changed = true, verification = Verification.MATCH)
    }
    override suspend fun back(): ActionResult { calls += "back"; return ActionResult.Ok(changed = false) }
    override suspend fun waitStable(timeoutMs: Long) = true
}

/** 按脚本回答的决策模型:每项是按说明挑选项的 [Pick],或要抛出的异常。 */
internal class FakeDecision(vararg script: Any) : DecisionModel {
    class Pick(val what: String, val confidence: Double, val before: () -> Unit = {})
    override val name = "fake-decision"
    override val contextTokens = 262_144
    private val script = script.toMutableList()
    val inputs = mutableListOf<String>()
    val options = mutableListOf<Map<String, String>>()
    override suspend fun decide(input: String, questions: Map<String, DecisionQuestion>): DecisionReply {
        inputs += input
        val (key, q) = questions.entries.single()
        val choice = q as DecisionQuestion.Choice
        options += choice.options
        val next = script.removeAt(0)
        if (next is Throwable) throw next
        val p = next as Pick
        p.before()
        val id = choice.options.keys.firstOrNull { it == p.what } ?: choice.options.entries.first { p.what in it.value }.key
        return DecisionReply(mapOf(key to DecisionAnswer.Choice(id, mapOf(id to p.confidence), p.confidence)), Usage(400, 0))
    }
}

class DelegateRunnerTest {
    private val events = mutableListOf<RunEvent>()
    private val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }
    private val home = mapOf(
        "home" to listOf("消息", "设置"),
        "settings" to listOf("WLAN", "蓝牙", "返回首页"),
        "wlan" to listOf("WLAN 开关", "发送日志"),
        "search" to listOf("输入搜索", "搜索"),
    )
    private val links = mapOf("设置" to "settings", "WLAN" to "wlan", "返回首页" to "home")

    private fun runner(d: ToolDispatcher, decision: DecisionModel, guards: OperationGuards = OperationGuards(), steer: () -> Boolean = { false }) =
        DelegateRunner(d, guards, ActionExecutor(d, guards, ConfirmationGate(), gate = {}, recorder = ActionRecorder(sink)), decision, steerWaiting = steer)

    private fun args(goal: String = "open WLAN settings", texts: List<String> = emptyList(), max: Int = 20) = DelegateArgs(goal, texts, max)
    private fun pick(what: String, c: Double = 0.95, before: () -> Unit = {}) = FakeDecision.Pick(what, c, before)
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.actions() = this["actions"]!!.jsonArray.map { it.jsonObject }

    @Test fun multiStepToDone() = runTest {
        val s = FlowScreen("home", home, links)
        val decision = FakeDecision(pick("Tap button \"设置\""), pick("Tap button \"WLAN\""), pick("DONE", 0.99))
        val r = runner(toolDispatcher(s), decision).run(args(), "phone", "c1", 4).content
        assertEquals("done", r.s("status")); assertEquals("done", r.s("reason")); assertEquals("true", r.s("ok"))
        assertEquals(listOf("tap 设置", "tap WLAN"), s.calls)
        assertEquals(2, r["stepsUsed"]!!.jsonPrimitive.int)
        assertEquals(listOf("phone_tap", "phone_tap"), r.actions().map { it.s("tool") })
        assertEquals(listOf("true", "true"), r.actions().map { it.s("changed") })
        assertTrue(r.actions()[0].s("target").contains("设置"))
        // 交回时读了一屏,和读屏工具的结果一样
        val screen = r["screen"]!!.jsonObject
        assertTrue(screen["elements"]!!.jsonArray.any { it.jsonObject.s("name") == "WLAN 开关" })
        // 每一步给决策模型的:局部目标、做过的动作(界面变没变)、此刻的屏
        assertTrue("Local goal: open WLAN settings" in decision.inputs[0])
        assertTrue("(none)" in decision.inputs[0])
        assertTrue("1. Tap button \"设置\" [@e2] -> screen changed" in decision.inputs[1])
        assertTrue("\"WLAN\"" in decision.inputs[1])
        // 记录:决策模型每次一条 role=decision,子步和交回时的读屏来源都是 delegate、同属第 4 步
        val decisions = events.filterIsInstance<RunEvent.ModelReply>()
        assertEquals(3, decisions.size)
        assertTrue(decisions.all { it.role == "decision" && it.model == "fake-decision" && it.inputTokens == 400 && it.step == 4 && !it.acted })
        val results = events.filterIsInstance<RunEvent.ToolResult>()
        assertEquals(listOf("phone_tap", "phone_tap", "phone_observe"), results.map { it.tool })
        assertTrue(results.all { it.source == "delegate" && it.step == 4 })
        assertEquals(listOf("c1.1", "c1.2", "c1.screen"), results.map { it.callId })
    }

    @Test fun blockedHandsBackWithoutActing() = runTest {
        val s = FlowScreen("home", home, links)
        val r = runner(toolDispatcher(s), FakeDecision(pick("BLOCKED", 0.9))).run(args(), "phone", "c1", 1).content
        assertEquals("blocked", r.s("status")); assertEquals("decision_blocked", r.s("reason")); assertEquals("false", r.s("ok"))
        assertTrue(s.calls.isEmpty())
        assertEquals(0, r["stepsUsed"]!!.jsonPrimitive.int)
        assertNotNull(r["screen"])
    }

    @Test fun lowConfidencePickIsNotRun() = runTest {
        val s = FlowScreen("home", home, links)
        val r = runner(toolDispatcher(s), FakeDecision(pick("Tap button \"设置\"", 0.6))).run(args(), "phone", "c1", 1).content
        assertEquals("low_confidence", r.s("reason")); assertEquals("blocked", r.s("status"))
        assertEquals("0.6", r.s("confidence"))
        assertTrue(s.calls.isEmpty())
        // 置信度低的 DONE 也不算完成
        val r2 = runner(toolDispatcher(FlowScreen("home", home, links)), FakeDecision(pick("DONE", 0.79))).run(args(), "phone", "c2", 1).content
        assertEquals("low_confidence", r2.s("reason"))
    }

    @Test fun accountOrDeviceChangingPickIsHandedBackUnexecuted() = runTest {
        for (name in listOf("退出登录", "Log out", "卸载")) {
            val s = FlowScreen("me", mapOf("me" to listOf("收藏", name)), emptyMap())
            val r = runner(toolDispatcher(s), FakeDecision(pick("Tap button \"$name\"", 0.99))).run(args(), "phone", "c1", 1).content
            assertEquals(name, "needs_main_model", r.s("reason"))
            assertEquals("false", r["pending"]!!.jsonObject.s("executed"))
            assertTrue(s.calls.isEmpty())
        }
    }

    @Test fun irreversiblePickIsHandedBackUnexecuted() = runTest {
        val s = FlowScreen("wlan", home, links)
        val r = runner(toolDispatcher(s), FakeDecision(pick("Tap button \"发送日志\"", 0.99))).run(args(), "phone", "c1", 1).content
        assertEquals("needs_main_model", r.s("reason")); assertEquals("blocked", r.s("status"))
        val pending = r["pending"]!!.jsonObject
        assertEquals("phone_tap", pending.s("tool")); assertTrue(pending.s("target").contains("发送日志")); assertEquals("false", pending.s("executed"))
        assertTrue(s.calls.isEmpty())
        assertTrue(events.filterIsInstance<RunEvent.ToolResult>().none { it.tool == "phone_tap" })
    }

    @Test fun stalePickIsRejectedNotRunNotLogged() = runTest {
        val s = FlowScreen("home", home, links)
        // 选的时候界面换了:选中的「设置」已不在,不点、不记,重读再选
        val decision = FakeDecision(pick("Tap button \"设置\"", before = { s.go("settings") }), pick("DONE", 0.99))
        val r = runner(toolDispatcher(s), decision).run(args(), "phone", "c1", 1).content
        assertEquals("done", r.s("status"))
        assertTrue(s.calls.isEmpty())
        assertEquals(0, r["stepsUsed"]!!.jsonPrimitive.int)
        assertTrue(events.filterIsInstance<RunEvent.ToolResult>().none { it.tool == "phone_tap" })
        assertTrue("\"WLAN\"" in decision.inputs[1])
        // 连着两次都过期就交回
        val s2 = FlowScreen("home", home, links)
        val again = FakeDecision(pick("Tap button \"设置\"", before = { s2.go("settings") }), pick("Tap button \"WLAN\"", before = { s2.go("home") }))
        assertEquals("stale_screen", runner(toolDispatcher(s2), again).run(args(), "phone", "c2", 1).content.s("reason"))
        assertTrue(s2.calls.isEmpty())
    }

    @Test fun twoUnchangedStepsHandBack() = runTest {
        val s = FlowScreen("home", home, links)
        val r = runner(toolDispatcher(s), FakeDecision(pick("Tap button \"消息\""), pick("Tap button \"消息\""), pick("DONE", 0.99)))
            .run(args(), "phone", "c1", 1).content
        assertEquals("no_change", r.s("reason"))
        assertEquals(listOf("tap 消息", "tap 消息"), s.calls)
        assertEquals(listOf("false", "false"), r.actions().map { it.s("changed") })
    }

    @Test fun stepCapStops() = runTest {
        val s = FlowScreen("home", home, links)
        val r = runner(toolDispatcher(s), FakeDecision(pick("Tap button \"设置\""), pick("Tap button \"返回首页\""), pick("Tap button \"设置\"")))
            .run(args(max = 2), "phone", "c1", 1).content
        assertEquals("stopped", r.s("status")); assertEquals("step_limit", r.s("reason"))
        assertEquals(listOf("tap 设置", "tap 返回首页"), s.calls)
        assertEquals(2, r["maxSteps"]!!.jsonPrimitive.int)
    }

    @Test fun decisionErrorStopsAndPersistentOnesDisable() = runTest {
        val s = FlowScreen("home", home, links)
        val auth = runner(toolDispatcher(s), FakeDecision(ModelError.Auth()))
        val r = auth.run(args(), "phone", "c1", 1).content
        assertEquals("stopped", r.s("status")); assertEquals("decision_error", r.s("reason")); assertEquals("Auth", r.s("error"))
        assertTrue(auth.disable)
        val net = runner(toolDispatcher(FlowScreen("home", home, links)), FakeDecision(ModelError.Network()))
        assertEquals("decision_error", net.run(args(), "phone", "c2", 1).content.s("reason"))
        assertFalse(net.disable)
        assertTrue(s.calls.isEmpty())
    }

    @Test fun typesOnlySuppliedTexts() = runTest {
        val s = FlowScreen("search", home, links)
        val decision = FakeDecision(pick("Type \"咖啡\""), pick("DONE", 0.99))
        val r = runner(toolDispatcher(s), decision).run(args("search for coffee", texts = listOf("咖啡")), "phone", "c1", 1).content
        assertEquals("done", r.s("status"))
        assertEquals(listOf("type 咖啡"), s.calls)
        assertEquals("0", r.actions().single().s("textIndex"))
        // 给模型的结果里不带输入的原文(运行记录同样不带)
        assertFalse("咖啡" in r.actions().single().toString())
        val typed = decision.options[0].values.filter { it.startsWith("Type ") }
        assertEquals(listOf("Type \"咖啡\" into field \"输入搜索\" [@e1]"), typed)
    }

    @Test fun userMessageFinishesCurrentStepThenHandsBack() = runTest {
        val s = FlowScreen("home", home, links)
        var steered = false
        val decision = FakeDecision(pick("Tap button \"设置\"", before = { steered = true }), pick("Tap button \"WLAN\""))
        val r = runner(toolDispatcher(s), decision, steer = { steered }).run(args(), "phone", "c1", 1).content
        assertEquals("user_message", r.s("reason")); assertEquals("stopped", r.s("status"))
        assertEquals(listOf("tap 设置"), s.calls)
    }

    @Test fun worksOnTheBrowserWithItsOwnTools() = runTest {
        val web = FakeWebPort()
        val d = toolDispatcher(FakeScreen(), web = web)
        val decision = FakeDecision(pick("Tap button \"搜索\""), pick("DONE", 0.99))
        val r = runner(d, decision).run(args("run the search"), "web", "c1", 1).content
        assertEquals("done", r.s("status"))
        assertTrue(web.calls.any { it.startsWith("tapRef @e1") })
        assertEquals("web_tap", r.actions().single().s("tool"))
        assertTrue(decision.options[0].values.all { !it.contains("phone_") })
        assertTrue(decision.inputs[0].contains("Site: shop.example.com"))
        // 页面弹着框:不选,交回
        web.dialog = PageDialog(PageDialog.ALERT, "确定离开?")
        val r2 = runner(d, FakeDecision()).run(args("run the search"), "web", "c2", 1).content
        assertEquals("dialog", r2.s("reason"))
    }

    @Test fun parseChecksArguments() {
        assertEquals(DelegateArgs("open WLAN", listOf("a"), 20), DelegateRunner.parse("""{"goal":" open WLAN ","texts":["a","a"]}""").first)
        assertEquals(5, DelegateRunner.parse("""{"goal":"g","maxSteps":5}""").first!!.maxSteps)
        assertTrue(DelegateRunner.parse("""{"goal":"g","maxSteps":21}""").second!!.startsWith("maxSteps"))
        assertTrue(DelegateRunner.parse("""{"maxSteps":3}""").second!!.startsWith("goal"))
        assertTrue(DelegateRunner.parse("""{"goal":"g","texts":"a"}""").second!!.startsWith("texts"))
        assertTrue(DelegateRunner.parse("""{"goal":"g","steps":[]}""").second!!.startsWith("steps"))
        assertEquals("""{"goal":"g","texts":["***","***"]}""", DelegateRunner.redactArgs("""{"goal":"g","texts":["123456","x"]}"""))
    }
}
