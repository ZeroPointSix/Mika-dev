package com.wangzan101.mika.core.delegate

import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.UiStates
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegateCandidatesTest {
    private val tools = setOf("phone_tap", "phone_scroll", "phone_type_text", "phone_back")
    private val toolOf = { base: String -> "phone_$base".takeIf { it in tools } }

    private fun el(
        n: Int, name: String, clickable: Boolean = true, editable: Boolean = false, scrollable: Boolean = false,
        bounds: List<Int> = listOf(0, n * 10, 999, n * 10 + 8), secret: Boolean = false, search: Boolean = false, states: Set<String> = emptySet(),
    ) = UiElement("@e$n", if (editable) "EditText" else "button", name, 0, 0, bounds, null, clickable, editable, scrollable,
        secret = secret, searchField = search, states = states)

    private fun snap(vararg e: UiElement) = ScreenSnapshot("p1", "com.x", e.toList(), 1080, 2400)

    @Test fun idsAreZeroPaddedSoSortedOrderIsScreenOrder() {
        val s = snap(*Array(150) { el(it + 1, "item ${it + 1}", bounds = listOf(0, it % 90, 999, it % 90 + 5)) })
        val set = DelegateCandidates.build(s, toolOf, emptyList(), 262_144)
        val ids = set.options.map { it.id }
        assertTrue(ids.size <= DelegateCandidates.MAX_OPTIONS)
        assertEquals(ids, ids.sorted())
        assertEquals("o001", ids.first())
        assertTrue(ids.all { Regex("[A-Za-z0-9_-]{1,64}").matches(it) })
        // 元素的候选按屏上顺序,返回总在最后,超出的数报在 trimmed 里
        assertEquals("Tap button \"item 1\" [@e1]", set.options.first().description)
        assertEquals("phone_back", set.options.last().tool)
        assertTrue(set.trimmed > 0)
        // DONE/BLOCKED 是大写,排在所有编号前面,不打乱编号的顺序
        assertEquals(listOf(DelegateCandidates.BLOCKED, DelegateCandidates.DONE) + ids, (ids + DelegateCandidates.DONE + DelegateCandidates.BLOCKED).sorted())
    }

    @Test fun smallContextTrimsOptionsAndScreen() {
        val s = snap(*Array(80) { el(it + 1, "a fairly long element name number ${it + 1}", bounds = listOf(0, it % 90, 999, it % 90 + 5)) })
        val big = DelegateCandidates.build(s, toolOf, emptyList(), 262_144)
        val small = DelegateCandidates.build(s, toolOf, emptyList(), 2_000)
        assertTrue(small.options.size < big.options.size)
        assertTrue(small.options.sumOf { it.description.length } <= 1_000)
        assertTrue(small.screen.length <= 500)
        assertTrue("more elements not shown" in small.screen)
    }

    @Test fun typesOnlySuppliedTextsIntoEditableNonSecretFields() {
        val s = snap(el(1, "搜索", editable = true, search = true), el(2, "密码", editable = true, secret = true), el(3, "确定"))
        val none = DelegateCandidates.build(s, toolOf, emptyList(), 262_144)
        assertTrue(none.options.none { it.tool == "phone_type_text" })
        val set = DelegateCandidates.build(s, toolOf, listOf("咖啡", "奶茶"), 262_144)
        val typed = set.options.filter { it.tool == "phone_type_text" }
        // 两段字各一条,搜索框另有回车;密码框不生成
        assertEquals(4, typed.size)
        assertEquals(setOf("咖啡", "奶茶"), typed.map { it.args["text"]!!.jsonPrimitive.content }.toSet())
        assertTrue(typed.all { it.args["ref"]!!.jsonPrimitive.content == "@e1" && it.args["snapshotId"]!!.jsonPrimitive.content == "p1" })
        assertEquals(listOf(0, 0, 1, 1), typed.map { it.textIndex })
        assertEquals(2, typed.count { it.args["pressEnter"] != null })
        assertTrue(set.options.none { it.args["ref"]?.jsonPrimitive?.content == "@e2" && it.tool == "phone_type_text" })
        // 屏幕文字里密码框不带字
        assertTrue("(password field)" in set.screen)
        assertFalse("\"密码\"" in set.screen)
    }

    @Test fun skipsDisabledInvalidBoundsAndMissingTools() {
        val s = snap(el(1, "可用"), el(2, "不可用", states = setOf(UiStates.DISABLED)), el(3, "无边界", bounds = listOf(5, 5, 5, 5)),
            el(4, "列表", clickable = false, scrollable = true, bounds = listOf(0, 100, 999, 900)))
        val set = DelegateCandidates.build(s, toolOf, emptyList(), 262_144)
        assertEquals(listOf("Tap button \"可用\" [@e1]", "Scroll down inside button \"列表\" [@e4]", "Scroll up inside button \"列表\" [@e4]", "Go back (Back key)"),
            set.options.map { it.description })
        assertEquals("499", set.options[1].args["x"]!!.jsonPrimitive.content)
        // 没有可滚动的元素时给整页滚动;这块屏没有返回工具就不给返回
        val noBack = DelegateCandidates.build(snap(el(1, "可用")), { b -> "web_$b".takeIf { b != "back" } }, emptyList(), 262_144)
        assertEquals(listOf("web_tap", "web_scroll", "web_scroll"), noBack.options.map { it.tool })
        assertTrue(noBack.options.none { it.description.contains("Back") })
    }
}
