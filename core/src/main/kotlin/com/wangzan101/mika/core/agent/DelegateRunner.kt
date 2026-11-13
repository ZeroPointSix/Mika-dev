package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.access.AppAccessDenied
import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionOutcome
import com.wangzan101.mika.core.action.ConfirmDecision
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.delegate.Candidate
import com.wangzan101.mika.core.delegate.CandidateSet
import com.wangzan101.mika.core.delegate.DelegateCandidates
import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.DecisionAnswer
import com.wangzan101.mika.core.model.DecisionModel
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.DecisionQuestion
import com.wangzan101.mika.core.model.DecisionReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Until
import com.wangzan101.mika.core.tool.UntilOutcome
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.tool.strArray
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.TreeAdequacy
import com.wangzan101.mika.core.uistate.TreeQuality
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.UntilWait
import com.wangzan101.mika.core.uistate.interfaces
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs

const val DELEGATE_STEPS_TOOL = "delegate_steps"

/** delegate_steps 的参数:局部目标、允许输入的原文、最多做几步。 */
data class DelegateArgs(val goal: String, val texts: List<String>, val maxSteps: Int)

/**
 * 委托执行:主模型给一个局部目标,决策模型在程序从当前屏生成的候选动作里一次选一个([DelegateCandidates]),
 * 选中的那一步作为对应的屏幕工具调用经 [ActionExecutor] 执行(和单步、一批同一个入口:守卫、关卡、不可逆判定、节奏、效果探测、记录照旧),
 * 再读屏、再选,直到交回主模型。是 [BatchRunner] 的兄弟:一批是主模型给好的已知动作,这里每一步由决策模型按屏幕挑。
 * 交回不写摘要:只有状态和原因、这次真做了的动作、交回时的一屏(经读屏工具读,进证据账本)。
 * 对步数上限整个委托算一步;每个子步对守卫和运行记录各算一个动作(运行记录里 source = "delegate"),
 * 每次决策模型调用记一条 role = decision 的 [RunEvent.ModelReply](用量、耗时)和一条 delegate_pick 状态。
 */
class DelegateRunner(
    private val tools: ToolDispatcher,
    private val guards: OperationGuards,
    private val executor: ActionExecutor,
    private val decision: DecisionModel,
    /** 每个子步的守卫结论交回循环,计入卡住提醒与重复输入(和 [BatchRunner] 同一个口子);返回要附给模型的建议。 */
    private val noteVerdicts: (step: BatchStep, exec: ToolExecution?, verdicts: List<GuardVerdict>) -> String? = { _, _, _ -> null },
    /** 用户此刻有没有新插话在等:有就做完手上这一步,交回主模型。 */
    private val steerWaiting: () -> Boolean = { false },
) {
    /** 所有子步里出现过的守卫结论。 */
    val verdicts = mutableListOf<GuardVerdict>()
    private var advice: String? = null

    /** 子步里为节奏停留的时间之和;运行记录算作等待。 */
    var waitedMs = 0L
        private set

    /** 决策模型出了重试也没用的错(Key、额度、模型名……):循环据此停用本任务余下的委托。 */
    var disable = false
        private set

    /** 交回的状态、原因;[pending] 是选中了但没做的那一步,[extra] 并进结果。 */
    private class End(val status: String, val reason: String, val pending: Candidate? = null, val extra: JsonObject = JsonObject(emptyMap()))

    /** 做过的一步:[changed] 是执行入口报的界面有没有变。 */
    private class Done(val c: Candidate, val changed: Boolean?, val ok: Boolean, val error: String?)

    suspend fun run(a: DelegateArgs, surface: String, callId: String, step: Int): ToolExecution {
        val ui = tools.interfaces
        val session = ui.sessions[surface] ?: return unavailable("no_screen", "There is no screen to act on in this task.")
        val family = session.surface.family
        val offered = tools.definitions().map { it.name }.toSet()
        val toolOf = { base: String -> family.tool(base).takeIf { it in offered } }
        val observe = family.tool("observe")
        val done = mutableListOf<Done>()
        val seen = mutableListOf<Observation>()
        var prev: ScreenSnapshot? = null
        var unchanged = 0
        var stale = 0
        var rounds = 0
        var end: End
        while (true) {
            if (done.size >= a.maxSteps) { end = End(STOPPED, "step_limit"); break }
            if (rounds > 0 && steerWaiting()) { end = End(STOPPED, NEW_MESSAGE_REASON); break }
            // 上一步的界面还在变时读到的是过渡画面
            if (rounds > 0) tools.settle(SETTLE_MS)
            rounds++
            val s = try {
                // 读屏前先过应用访问:被挡的 App 的屏幕内容不读、不发给决策模型
                ui.checkAppAccess(tools.declared(observe))
                session.snapshot(internal = true)
            } catch (e: AppAccessDenied) {
                end = End(STOPPED, "app_access_denied", extra = buildJsonObject { put("error", e.code); put("message", e.message) }); break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                end = End(BLOCKED_STATUS, "screen_unreadable"); break
            }
            val problem = screenProblem(prev, s)
            if (problem != null) { end = End(BLOCKED_STATUS, problem); break }
            prev = s
            val set = DelegateCandidates.build(s, toolOf, a.texts, decision.contextTokens)
            val reply = ask(a, set, done, step)
            if (reply == null) { end = End(STOPPED, "decision_error", extra = buildJsonObject { lastError?.let { put("error", it) } }); break }
            val pick = reply.answers[QUESTION] as? DecisionAnswer.Choice
            if (pick == null) { end = End(STOPPED, "decision_error", extra = buildJsonObject { put("error", "no_choice") }); break }
            val c = set[pick.choice]
            note("${pick.choice} ${c?.let { "${it.tool} ${it.label}" } ?: ""} ${"%.3f".format(pick.confidence)}", step)
            val confidence = buildJsonObject { put("choice", c?.description ?: pick.choice); put("confidence", round3(pick.confidence)) }
            // 低于阈值:不管选的是什么都不照做,交回主模型
            if (pick.confidence < MIN_CONFIDENCE) { end = End(BLOCKED_STATUS, "low_confidence", extra = confidence); break }
            if (pick.choice == DelegateCandidates.DONE) { end = End(DONE_STATUS, "done"); break }
            if (pick.choice == DelegateCandidates.BLOCKED) { end = End(BLOCKED_STATUS, "decision_blocked"); break }
            if (c == null) { end = End(BLOCKED_STATUS, "invalid_choice", extra = confidence); break }
            // 选完再看一眼:目标元素已不在原处(界面在选的时候变了)就不做、不记成动作,重读再选
            if (!stillThere(session.peek(), c.element)) {
                if (++stale >= MAX_STALE) { end = End(BLOCKED_STATUS, "stale_screen"); break }
                note("stale ${c.id}", step)
                continue
            }
            stale = 0
            // 可能不可逆、要确认、只该由主模型做的:不执行,交回
            if (needsMain(c)) { end = End(BLOCKED_STATUS, "needs_main_model", pending = c); break }
            val out = executor.run(Part.ToolCall("$callId.${done.size + 1}", c.tool, c.args.toString()), Origin.Delegate(step))
            val batchStep = BatchStep(c.tool, c.args.toString())
            note(batchStep, null, out.before)
            val exec = when (out) {
                is ActionOutcome.Ran -> out.exec
                is ActionOutcome.Cancelled -> { end = End(STOPPED, "cancelled"); break }
                is ActionOutcome.Refused -> {
                    end = when {
                        out.hit != null || out.code in CONFIRM_CODES -> End(BLOCKED_STATUS, "needs_main_model", pending = c)
                        AppAccessDenied.isAppAccessCode(out.code) ->
                            End(STOPPED, "app_access_denied", extra = buildJsonObject { put("error", out.code); (out.content["message"] as? JsonPrimitive)?.let { put("message", it) } })
                        out.code == "guard_stopped" -> End(STOPPED, "guard_stopped")
                        else -> End(BLOCKED_STATUS, "action_refused", pending = c, extra = buildJsonObject { put("error", out.code) })
                    }
                    break
                }
            }
            // 节奏停留里用户插了话:这一步没做,交回去送那句话
            if (exec.content["error"] == JsonPrimitive(BatchRunner.NEW_MESSAGE)) {
                waitedMs += exec.waitMs
                end = End(STOPPED, NEW_MESSAGE_REASON); break
            }
            seen += exec.observations
            waitedMs += exec.waitMs
            note(batchStep, exec, out.after)
            val content = exec.content
            val ok = content["ok"] == JsonPrimitive(true)
            val error = (content["error"] as? JsonPrimitive)?.contentOrNull
            done += Done(c, exec.changed, ok, error)
            val hard = when {
                !ok -> End(BLOCKED_STATUS, "action_failed", extra = buildJsonObject { put("error", error ?: "failed") })
                content["verification"] == JsonPrimitive("mismatch") -> End(BLOCKED_STATUS, "action_failed", extra = buildJsonObject { put("error", "action_not_applied") })
                content["app_switched"] != null || content["opened"] != null -> End(BLOCKED_STATUS, "app_changed")
                content["limitReached"] != null -> End(STOPPED, "limit_reached")
                ((content["effect"] as? JsonObject)?.get("treeBlind")) == JsonPrimitive(true) -> End(BLOCKED_STATUS, "tree_blind")
                else -> null
            }
            if (hard != null) { end = hard; break }
            if (guards.stop != null) { end = End(STOPPED, "guard_stopped"); break }
            when (exec.changed) {
                false -> if (++unchanged >= MAX_UNCHANGED) { end = End(BLOCKED_STATUS, "no_change"); break }
                true -> unchanged = 0
                null -> Unit
            }
        }
        executor.recorder.event(RunEvent.State(executor.recorder.now(), "delegate_end",
            "${end.status} ${end.reason} ${done.size}/${a.maxSteps}"))
        // 交回时读一屏:经读屏工具(应用访问、守卫、证据账本、运行记录照常),和主模型自己读屏一样
        val look = if (end.reason in NO_LOOK) null else (executor.run(Part.ToolCall("$callId.screen", observe, "{}"), Origin.Delegate(step)) as? ActionOutcome.Ran)?.exec
        look?.let { seen += it.observations; waitedMs += it.waitMs }
        return finish(a, end, done, look, seen)
    }

    /** 和上一次读屏比:页面弹框、别的包的窗口盖上来、同一 App 多出窗口、换了 App;树读不到。没问题为 null。 */
    private fun screenProblem(prev: ScreenSnapshot?, s: ScreenSnapshot): String? {
        if (prev == null && s.web?.dialog != null) return "dialog"
        if (prev != null) when (UntilWait.unexpected(prev, s, Until())) {
            UntilOutcome.POPUP -> return "dialog"
            UntilOutcome.SYSTEM_DIALOG -> return "other_window"
            UntilOutcome.APP_SWITCHED -> return "app_changed"
        }
        val q = TreeAdequacy.ofTree(s).quality
        return if (q == TreeQuality.Unusable || q == TreeQuality.Partial || q == TreeQuality.Protected) "tree_blind" else null
    }

    private var lastError: String? = null

    /** 问一次决策模型;出错为 null(原因在 [lastError],重试也没用的错同时置 [disable])。 */
    private suspend fun ask(a: DelegateArgs, set: CandidateSet, done: List<Done>, step: Int): DecisionReply? {
        val options = buildMap {
            set.options.forEach { put(it.id, it.description) }
            put(DelegateCandidates.DONE, "The goal is already reached on the current screen.")
            put(DelegateCandidates.BLOCKED, "No listed action clearly moves toward the goal, or the goal needs judgement or an action that is not listed.")
        }
        val input = buildString {
            appendLine("Local goal: ${a.goal}")
            appendLine()
            appendLine("Actions already done for this goal:")
            if (done.isEmpty()) appendLine("(none)")
            done.forEachIndexed { i, d ->
                val effect = when { !d.ok -> "failed"; d.changed == true -> "screen changed"; d.changed == false -> "no visible change"; else -> "change unknown" }
                appendLine("${i + 1}. ${d.c.description} -> $effect")
            }
            appendLine()
            appendLine("Current screen:")
            append(set.screen)
            if (set.trimmed > 0) append("\n(${set.trimmed} more possible actions were left out of the options)")
        }
        val started = executor.recorder.now()
        val reply = try {
            // 用量与本任务的 Token 预算由计量外壳记(MeteredDecisionModel,和对话模型一样),这里不再记一遍
            decision.decide(input, mapOf(QUESTION to DecisionQuestion.Choice(Prompts.role("delegate_decision"), options)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError.TaskBudgetExceeded) {
            // 整个运行共用一份预算:和主模型一样直接结束,不当成一次决策出错交回
            throw e
        } catch (e: Exception) {
            lastError = e::class.simpleName ?: "error"
            if (ModelCaller.lastsForRun(e)) disable = true
            executor.recorder.event(RunEvent.State(executor.recorder.now(), "delegate_error", RunEvent.clip(Redactor.text("${decision.name} ${e.message ?: lastError}"))))
            return null
        }
        val u = reply.usage
        executor.recorder.event(RunEvent.ModelReply(executor.recorder.now(), step, "", null, null, u?.inputTokens, u?.outputTokens, u?.cachedInputTokens,
            latencyMs = executor.recorder.now() - started, model = decision.name.ifEmpty { null }, role = ModelCaller.ROLE_DECISION))
        return reply
    }

    /** 这一步只该由主模型决定:可能不可逆(按上一次读屏判)、工具声明要问、或是主模型才能给的调用。 */
    private fun needsMain(c: Candidate): Boolean =
        ModelRouter.mainOnly(c.tool, tools.verb(c.tool)) || tools.approvalRisk(c.tool, c.args)?.ask != null ||
            ConfirmDecision.mayBeIrreversible(tools, c.tool, c.args) || changesAccountOrDevice(c.label)

    /**
     * 退出登录、切换或注销账号、卸载、重置这类动作:不可逆判定不管它们(主模型自己走时照常能点),
     * 委托里一律不替主模型做,连同选中的那一步交回。只看元素的名字,宁可多交回。
     */
    private fun changesAccountOrDevice(label: String): Boolean {
        val name = label.lowercase().filterNot { it.isWhitespace() }
        return ACCOUNT_OR_DEVICE_WORDS.any { it in name }
    }

    private fun note(s: BatchStep, exec: ToolExecution?, v: List<GuardVerdict>) {
        verdicts += v
        noteVerdicts(s, exec, v)?.let { advice = it }
    }

    private fun note(detail: String, step: Int) =
        executor.recorder.event(RunEvent.State(executor.recorder.now(), "delegate_pick", RunEvent.clip("$step $detail")))

    private fun finish(a: DelegateArgs, end: End, done: List<Done>, look: ToolExecution?, seen: List<Observation>): ToolExecution {
        val content = buildJsonObject {
            put("ok", end.status == DONE_STATUS)
            put("status", end.status)
            put("reason", end.reason)
            put("message", MESSAGES[end.reason] ?: "Control is back with you.")
            end.extra.forEach { (k, v) -> put(k, v) }
            end.pending?.let { p -> put("pending", buildJsonObject { put("tool", p.tool); put("target", p.label); put("executed", false) }) }
            put("stepsUsed", done.size)
            put("maxSteps", a.maxSteps)
            put("actions", JsonArray(done.map { d ->
                buildJsonObject {
                    put("tool", d.c.tool)
                    put("target", d.c.label)
                    (d.c.args["direction"] as? JsonPrimitive)?.let { put("direction", it) }
                    d.c.textIndex?.let { put("textIndex", it) }
                    d.changed?.let { put("changed", it) }
                    if (!d.ok) put("ok", false)
                    d.error?.let { put("error", it) }
                }
            }))
            advice?.let { put("advice", it) }
            look?.content?.let { put("screen", JsonObject(it - "ok")) }
            put("counts_as_failure", false)
        }
        return ToolExecution(content, changed = done.lastOrNull()?.changed, images = look?.images.orEmpty(), imageIds = look?.imageIds.orEmpty(),
            screenElements = look?.screenElements, place = look?.place, observations = seen)
    }

    private fun unavailable(reason: String, message: String) = ToolExecution(buildJsonObject {
        put("ok", false); put("status", STOPPED); put("reason", reason); put("message", message); put("stepsUsed", 0); put("recoverable", true)
    })

    companion object {
        /** 名字里有这些词的元素,委托里不点(去空白、小写后比)。 */
        private val ACCOUNT_OR_DEVICE_WORDS = listOf(
            "退出登录", "退出登錄", "退出账号", "退出帳號", "登出", "切换账号", "切換帳號", "注销", "註銷", "卸载", "卸載",
            "恢复出厂", "恢復出廠", "重置", "格式化", "抹掉", "解除绑定", "解绑", "解綁",
            "logout", "signout", "switchaccount", "deleteaccount", "uninstall", "factoryreset", "eraseall", "unlink", "deactivate",
        )
        /** 决策模型的置信度低于它就不照做,交回主模型(评测后再校准)。 */
        const val MIN_CONFIDENCE = 0.8
        const val DEFAULT_STEPS = 20
        const val MAX_STEPS = 20
        const val MAX_TEXTS = 5
        private const val GOAL_MAX = 500
        private const val TEXT_MAX = 200
        /** 连着这么多步界面都没变就交回。 */
        private const val MAX_UNCHANGED = 2
        /** 连着这么多次选完界面已变(选中的元素不在了)就交回。 */
        private const val MAX_STALE = 2
        /** 第二轮起读屏前等界面稳定的上限(和一批相同)。 */
        private const val SETTLE_MS = 1500L
        /** 选中元素前后位置的容差(0–999)。 */
        private const val BOUNDS_SLACK = 8
        private const val QUESTION = "next_action"

        const val DONE_STATUS = "done"
        const val BLOCKED_STATUS = "blocked"
        const val STOPPED = "stopped"
        const val NEW_MESSAGE_REASON = "user_message"
        /** 这些情况交回时不再读屏(读不了,或任务要取消)。 */
        private val NO_LOOK = setOf("app_access_denied", "cancelled", "no_screen")
        private val CONFIRM_CODES = setOf("confirmation_required", "confirmation_mismatch", "approval_unavailable", "confirmation_declined", "target_unresolved")

        private val MESSAGES = mapOf(
            "done" to "The step-picker reports the goal reached. Check the screen below before relying on it.",
            "decision_blocked" to "The step-picker found no listed action that clearly moves toward the goal. Decide the next step yourself from the screen below.",
            "low_confidence" to "The step-picker was not confident enough, so its pick was not run. Decide the next step yourself from the screen below.",
            "needs_main_model" to "The next action (pending) may be irreversible, needs confirmation, or is for you to decide. It was not run; decide it yourself.",
            "no_change" to "The last actions did not change the screen. Look at the screen below and try another way.",
            "dialog" to "A dialog or popup appeared. Handle it yourself.",
            "other_window" to "Another app's window covers the screen. Handle it yourself.",
            "app_changed" to "The app in front changed. Check where you are now.",
            "tree_blind" to "The screen's elements cannot be read well enough to pick actions from them. Continue yourself, looking at the screen if you can.",
            "screen_unreadable" to "The screen could not be read.",
            "stale_screen" to "The screen kept changing while actions were being picked. Continue yourself.",
            "invalid_choice" to "The step-picker gave an answer that is not one of the actions. Continue yourself.",
            "action_failed" to "An action failed or did not take effect. Check the screen below and continue yourself.",
            "action_refused" to "The next action (pending) was refused and not run. Continue yourself.",
            "step_limit" to "The step limit for this delegation was reached before the goal. Check the screen below; delegate again or continue yourself.",
            "decision_error" to "The step-picker could not be reached. Continue yourself step by step.",
            NEW_MESSAGE_REASON to "Stopped to handle a new message from the user. Read the message first, then decide what to do next.",
            "guard_stopped" to "Stopped by the operation guard.",
            "limit_reached" to "The site or app says a limit was reached.",
            "app_access_denied" to "This app is not allowed in Application access.",
            "cancelled" to "Cancelled.",
        )

        /** 选中的元素此刻是否还在原处:同角色、同名字、边界在容差内;没有目标的动作(返回、整页滚动)总在。 */
        internal fun stillThere(now: ScreenSnapshot?, e: UiElement?): Boolean {
            if (e == null) return true
            now ?: return false
            return now.elements.any { n ->
                n.role == e.role && n.name == e.name && n.bounds.size == 4 && e.bounds.size == 4 &&
                    n.bounds.indices.all { abs(n.bounds[it] - e.bounds[it]) <= BOUNDS_SLACK }
            }
        }

        private fun round3(d: Double) = Math.round(d * 1000) / 1000.0

        /** 校验参数;返回参数,或说明哪里不对。 */
        fun parse(argsJson: String): Pair<DelegateArgs?, String?> {
            val o = try { Json.parseToJsonElement(argsJson.ifBlank { "{}" }) as? JsonObject } catch (e: SerializationException) { null }
                ?: return null to "arguments: must be a JSON object"
            o.keys.firstOrNull { it !in setOf("goal", "texts", "maxSteps", "reason") }?.let { return null to "$it: unknown argument for $DELEGATE_STEPS_TOOL" }
            val goal = (o["goal"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null to "goal: required, a short description of the end state you want on this screen"
            if (goal.length > GOAL_MAX) return null to "goal: must be at most $GOAL_MAX characters"
            val texts = when (val t = o["texts"]) {
                null -> emptyList()
                is JsonArray -> t.mapIndexed { i, x ->
                    (x as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
                        ?: return null to "texts[${i + 1}]: must be a non-empty string"
                }
                else -> return null to "texts: must be an array of strings"
            }
            if (texts.size > MAX_TEXTS) return null to "texts: at most $MAX_TEXTS strings"
            texts.firstOrNull { it.length > TEXT_MAX }?.let { return null to "texts: each at most $TEXT_MAX characters" }
            val steps = when (val m = o["maxSteps"]) {
                null -> DEFAULT_STEPS
                is JsonPrimitive -> m.takeIf { !it.isString }?.intOrNull?.takeIf { it in 1..MAX_STEPS } ?: return null to "maxSteps: must be an integer, 1-$MAX_STEPS"
                else -> return null to "maxSteps: must be an integer, 1-$MAX_STEPS"
            }
            return DelegateArgs(goal, texts.distinct(), steps) to null
        }

        /** 运行记录用:要输入的原文一律打码。 */
        fun redactArgs(argsJson: String): String {
            val o = try { Json.parseToJsonElement(argsJson) as? JsonObject } catch (e: SerializationException) { null } ?: return Redactor.text(argsJson)
            val texts = o["texts"] as? JsonArray ?: return Redactor.text(argsJson)
            return Redactor.text(JsonObject(o + ("texts" to JsonArray(texts.map { JsonPrimitive(Redactor.MASK) }))).toString())
        }

        /** delegate_steps 的定义。 */
        fun spec(): ToolSpec = ToolDef(DELEGATE_STEPS_TOOL, Prompts.tool("delegate_steps", "maxSteps" to MAX_STEPS), mapOf(
            "goal" to str("The end state to reach on the current screen, e.g. 'the Wi-Fi settings page is open' or 'search results for the given text are shown'. Name exact labels from the screen when you know them."),
            "texts" to strArray("Exact strings it may type into input fields (up to $MAX_TEXTS). It types nothing else; leave out when no typing is needed.", MAX_TEXTS),
            "maxSteps" to int("Most actions to run, 1-$MAX_STEPS (default $DEFAULT_STEPS).", 1, MAX_STEPS),
        ), listOf("goal")).spec()
    }
}
