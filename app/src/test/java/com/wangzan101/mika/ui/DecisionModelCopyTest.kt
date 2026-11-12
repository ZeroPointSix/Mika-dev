package com.wangzan101.mika.ui

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.net.ProxyFailure
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.settings.DecisionModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DecisionModelCopyTest {
    @get:Rule val texts = ChineseTexts()

    @Test fun rowValueShowsModelOnlyWhenKeyIsSet() {
        assertEquals("未设置", DecisionModelCopy.value(DecisionModelConfig()))
        assertEquals("Gemini · gemini-decision-1", DecisionModelCopy.value(DecisionModelConfig(apiKey = "k")))
        assertEquals("Gemini · m2", DecisionModelCopy.value(DecisionModelConfig(apiKey = "k", model = "m2")))
    }

    @Test fun failuresAreExplained() {
        assertEquals("测试没通过：API Key 无效或没有权限", DecisionModelCopy.failure("m", ModelError.Auth()))
        assertEquals("找不到模型「m」，请检查模型名。", DecisionModelCopy.failure("m", ModelError.ModelNotFound("m")))
        assertTrue(DecisionModelCopy.failure("m", ModelError.Proxy(ProxyFailure.AuthFailed)).contains("网络代理"))
    }
}
