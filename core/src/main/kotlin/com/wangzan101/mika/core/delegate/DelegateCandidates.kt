package com.wangzan101.mika.core.delegate

import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.UiStates
import com.wangzan101.mika.core.net.UrlHost
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 委托里的一个候选动作:[id] 是给决策模型的选项名,[tool]/[args] 是交给执行入口的那次调用(和模型单步调用的写法一样),
 * [description] 是给决策模型看的一句英文,[label] 是动作作用的目标(动作记录用,不含要输入的字),
 * [element] 是它作用的元素(返回、整页滚动为 null),[textIndex] 是输入的字在主模型给的 texts 里的下标。
 */
data class Candidate(
    val id: String,
    val tool: String,
    val args: JsonObject,
    val description: String,
    val label: String,
    val element: UiElement? = null,
    val textIndex: Int? = null,
)

/** 一屏的候选动作(按屏上顺序,编号补零,按字母排也是这个顺序)和给决策模型看的屏幕文字。[trimmed] 是超出预算没给的候选数。 */
class CandidateSet(val options: List<Candidate>, val screen: String, val trimmed: Int) {
    private val byId = options.associateBy { it.id }
    operator fun get(id: String): Candidate? = byId[id]
}

/**
 * 从一次读屏生成候选动作,不问模型:看得见、可用、边界有效的元素可点;可滚动的元素上下滚;可编辑的输入框只输入主模型给的原文
 * (搜索框另有「输入后回车」);再加返回。手机、网页同一套:工具名由 [build] 的 toolOf 按这块屏的那一套给,没有的动作不生成。
 * 选项名是 `o001` 这样补零的编号:服务商会按选项名排序后再给模型,补零后排出来的顺序就是屏上的顺序;DONE、BLOCKED 另加。
 */
object DelegateCandidates {
    const val DONE = "DONE"
    const val BLOCKED = "BLOCKED"
    /** 候选动作最多几个(另加 DONE、BLOCKED;接口上限 255)。 */
    const val MAX_OPTIONS = 120
    /** 一次请求最多用多少字(一个字按一个 token 算,偏保守);再按决策模型的上下文的一半封顶。 */
    const val MAX_INPUT_CHARS = 16_000
    /** 每个输入框都生成每段字的输入:输入框太多时只取前几个。 */
    private const val MAX_FIELDS = 3
    private const val MAX_SCROLLABLES = 3
    private const val LABEL_CHARS = 60
    private const val LINE_CHARS = 120
    /** 选项说明、屏幕文字各占预算的比例;余下给局部目标和动作记录。 */
    private const val OPTIONS_SHARE = 0.4
    private const val SCREEN_SHARE = 0.4

    fun id(n: Int): String = "o%03d".format(n)

    /** 一次请求能用的字数:[contextTokens] 的一半,不超过 [MAX_INPUT_CHARS]。 */
    fun budget(contextTokens: Int): Int = (contextTokens / 2).coerceIn(1_000, MAX_INPUT_CHARS)

    /**
     * [toolOf] 把动词(tap、scroll、type_text、back)换成这块屏上提供给模型的工具名,不提供为 null;
     * [texts] 是主模型给的、允许输入的原文;[contextTokens] 是决策模型的上下文。
     */
    fun build(s: ScreenSnapshot, toolOf: (String) -> String?, texts: List<String>, contextTokens: Int): CandidateSet {
        val total = budget(contextTokens)
        val tap = toolOf("tap")
        val scroll = toolOf("scroll")
        val type = toolOf("type_text")
        val back = toolOf("back")
        val usable = s.elements.filter(::usable)
        // 返回、整页滚动放在最后,预算不够时先裁屏上元素的
        val tail = buildList {
            if (scroll != null && usable.none { it.scrollable }) {
                add(Draft(scroll, buildJsonObject { put("direction", "down") }, "Scroll the page down", "page"))
                add(Draft(scroll, buildJsonObject { put("direction", "up") }, "Scroll the page up", "page"))
            }
            back?.let { add(Draft(it, JsonObject(emptyMap()), "Go back (Back key)", "Back")) }
        }
        val drafts = mutableListOf<Draft>()
        var fields = 0
        var scrollables = 0
        for (e in usable) {
            val what = label(e)
            if (type != null && e.editable && !e.secret && texts.isNotEmpty() && fields < MAX_FIELDS) {
                fields++
                texts.forEachIndexed { i, t ->
                    val args = buildJsonObject { put("ref", e.ref); put("snapshotId", s.snapshotId); put("text", t) }
                    drafts += Draft(type, args, "Type \"${clip(t, LABEL_CHARS)}\" into $what", what, e, i)
                    // 搜索框按自己的语义回车就是搜索,不会发出去
                    if (e.searchField) drafts += Draft(type, JsonObject(args + ("pressEnter" to JsonPrimitive(true))),
                        "Type \"${clip(t, LABEL_CHARS)}\" into $what and press Enter to search", what, e, i)
                }
            }
            if (tap != null && e.clickable) {
                drafts += Draft(tap, buildJsonObject { put("ref", e.ref); put("snapshotId", s.snapshotId) }, "Tap $what", what, e)
            }
            if (scroll != null && e.scrollable && scrollables < MAX_SCROLLABLES) {
                scrollables++
                val (x, y) = center(e)
                for (dir in listOf("down", "up")) {
                    drafts += Draft(scroll, buildJsonObject { put("direction", dir); put("x", x); put("y", y) }, "Scroll $dir inside $what", what, e)
                }
            }
        }
        val room = MAX_OPTIONS - tail.size
        var used = tail.sumOf { it.description.length + 8 }
        val kept = mutableListOf<Draft>()
        for (d in drafts) {
            if (kept.size >= room || used + d.description.length + 8 > total * OPTIONS_SHARE) break
            kept += d
            used += d.description.length + 8
        }
        val options = (kept + tail).mapIndexed { i, d -> Candidate(id(i + 1), d.tool, d.args, d.description, d.label, d.element, d.textIndex) }
        return CandidateSet(options, screenText(s, (total * SCREEN_SHARE).toInt()), drafts.size - kept.size)
    }

    private class Draft(
        val tool: String, val args: JsonObject, val description: String, val label: String,
        val element: UiElement? = null, val textIndex: Int? = null,
    )

    /** 可作为动作目标:可用、边界有效且在屏内。 */
    private fun usable(e: UiElement): Boolean {
        if (UiStates.DISABLED in e.states) return false
        val b = e.bounds
        if (b.size != 4 || b[2] <= b[0] || b[3] <= b[1]) return false
        return b[2] > 0 && b[3] > 0 && b[0] < 999 && b[1] < 999
    }

    private fun center(e: UiElement): Pair<Int, Int> = ((e.bounds[0] + e.bounds[2]) / 2).coerceIn(0, 999) to ((e.bounds[1] + e.bounds[3]) / 2).coerceIn(0, 999)

    /** 元素的说法:角色、名字(没有时用输入框提示语、资源名)和编号,如 `button "搜索" [@e3]`。 */
    fun label(e: UiElement): String {
        val name = e.name.takeIf { it.isNotBlank() } ?: e.placeholder?.takeIf { it.isNotBlank() }
            ?: e.resourceId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val kind = if (e.editable) "${if (e.searchField) "search " else ""}field" else e.role
        return listOfNotNull(kind, name?.let { "\"${clip(it, LABEL_CHARS)}\"" }, "[${e.ref}]").joinToString(" ")
    }

    /**
     * 给决策模型看的这一屏:在哪(App 或网站)、页面弹框,再一行一个有意义的元素(编号、角色、名字、状态),超出 [maxChars] 截掉并说明。
     * 密码框只写是密码框,不写里面的字。
     */
    fun screenText(s: ScreenSnapshot, maxChars: Int): String {
        val head = buildList {
            val w = s.web
            if (w != null) add("Site: ${UrlHost.of(w.url)} - ${clip(w.title, LINE_CHARS)}") else add("App: ${s.packageName ?: s.unreadableApp ?: "unknown"}")
            w?.dialog?.let { add("Page dialog (${it.kind}): ${clip(it.message, LINE_CHARS)}") }
        }
        val lines = s.elements.filter { it.meaningful }.map { e ->
            val name = when {
                e.secret -> " (password field)"
                e.name.isNotBlank() -> " \"${clip(e.name, LINE_CHARS)}\""
                else -> ""
            }
            val flags = listOfNotNull("editable".takeIf { e.editable }, "focused".takeIf { e.focused }, "scrollable".takeIf { e.scrollable },
                "hint".takeIf { e.hint }) + e.states.sorted()
            "[${e.ref}] ${e.role}$name${if (flags.isEmpty()) "" else " (${flags.joinToString(", ")})"}"
        }
        val out = StringBuilder(head.joinToString("\n"))
        var shown = 0
        for (l in lines) {
            val left = lines.size - shown - 1
            val reserve = if (left > 0) 48 else 0
            if (out.length + l.length + 1 + reserve > maxChars) break
            out.append('\n').append(l)
            shown++
        }
        if (shown < lines.size) out.append("\n… ${lines.size - shown} more elements not shown")
        return out.toString()
    }

    private fun clip(s: String, max: Int): String {
        val one = s.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= max) one else one.take(max - 1) + "…"
    }
}
