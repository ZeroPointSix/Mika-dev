package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.net.ProxyFailure
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.DecisionModelConfig
import com.wangzan101.mika.settings.DecisionModelStore
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 决策模型设置里要的文字;不碰界面,单测直接测。 */
internal object DecisionModelCopy {
    val TITLE: String get() = tr(R.string.decision_title)
    val ROW: String get() = tr(R.string.decision_row)

    /** 模型页那一行下面:用的哪个模型,没填 Key 写「未设置」。 */
    fun value(config: DecisionModelConfig): String =
        if (config.configured) "${DecisionModelStore.providerName(config.kind)} · ${config.modelName}" else tr(R.string.decision_not_set)

    /** 测试没通过的原因:找不到模型、代理、超时各有说法,其余照服务商回的原话(和服务商的试调用一样)。 */
    fun failure(model: String, e: Throwable): String = when (e) {
        is TimeoutCancellationException -> tr(R.string.decision_test_timeout, model, (TEST_TIMEOUT_MS / 1000).toInt())
        is ModelError.ModelNotFound -> tr(R.string.decision_test_not_found, model)
        is ModelError.Proxy -> tr(R.string.decision_test_failed, tr(
            if (e.failure == ProxyFailure.AuthFailed) R.string.net_proxy_auth_failed else R.string.net_proxy_unreachable,
        ))
        else -> tr(R.string.decision_test_failed, e.message.orEmpty())
    }

    /** 拿 Gemini Key 的页面。 */
    const val KEY_PAGE = "https://aistudio.google.com/apikey"
    const val BASE_URL_PLACEHOLDER = "https://generativelanguage.googleapis.com"
    const val TEST_TIMEOUT_MS = 30_000L
}

/** 设置 › 模型 下面的一组:决策模型(实验)。点开是 [DecisionModelDialog]。 */
@Composable
internal fun DecisionModelSection(config: DecisionModelConfig, onOpen: () -> Unit) {
    GroupLabel(DecisionModelCopy.TITLE)
    GroupCard {
        ListRow(MikaIcons.Route, DecisionModelCopy.ROW, DecisionModelCopy.value(config), first = true, onClick = onOpen) { RowChevron() }
    }
}

/** 测试的状态:没测、在测、通过、没通过(带原因)。 */
private sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data object Passed : TestState
    data class Failed(val message: String) : TestState
}

/**
 * 决策模型的设置对话框:Key(必填才会用)、地址(可空)、模型名(可空,用默认)。「测试」按框里还没存的值真实调用一次;
 * 改了任一栏,上次的测试结果作废。Key 留空保存等于不用决策模型。说明里写明委托步骤的屏幕文字会发给这一家。
 */
@Composable
fun DecisionModelDialog(
    initial: DecisionModelConfig,
    store: DecisionModelStore,
    onDismiss: () -> Unit,
    onSave: (DecisionModelConfig) -> Unit,
) {
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var test by remember { mutableStateOf<TestState>(TestState.Idle) }
    val config = initial.copy(apiKey = apiKey, baseUrl = baseUrl, model = model)
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val c = MikaTheme.colors
    fun edited() { if (test != TestState.Running) test = TestState.Idle }
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(DecisionModelCopy.TITLE) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.decision_note), style = MikaType.Hint, color = c.ink3)
                OutlinedTextField(
                    apiKey, { apiKey = it; edited() },
                    Modifier.fillMaxWidth().padding(top = 8.dp).testTag("decision-key"),
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.decision_api_key)) },
                )
                // 没有能打开网页的应用时 openUri 会抛错:这时什么都不做
                TextButton({ runCatching { uri.openUri(DecisionModelCopy.KEY_PAGE) } }, Modifier.testTag("decision-get-key")) {
                    Text(stringResource(R.string.decision_get_key))
                }
                OutlinedTextField(
                    model, { model = it; edited() },
                    Modifier.fillMaxWidth().testTag("decision-model"),
                    singleLine = true,
                    label = { Text(stringResource(R.string.decision_model)) },
                    placeholder = { Text(config.copy(model = "").modelName) },
                )
                OutlinedTextField(
                    baseUrl, { baseUrl = it; edited() },
                    Modifier.fillMaxWidth().padding(top = 8.dp).testTag("decision-base-url"),
                    singleLine = true,
                    label = { Text(stringResource(R.string.decision_base_url)) },
                    placeholder = { Text(DecisionModelCopy.BASE_URL_PLACEHOLDER) },
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Start) {
                    TextButton(
                        {
                            if (!config.configured) {
                                test = TestState.Failed(tr(R.string.decision_missing_key))
                                return@TextButton
                            }
                            test = TestState.Running
                            scope.launch {
                                test = try {
                                    withContext(Dispatchers.IO) { withTimeout(DecisionModelCopy.TEST_TIMEOUT_MS) { store.test(config) } }
                                    TestState.Passed
                                } catch (e: TimeoutCancellationException) {
                                    TestState.Failed(DecisionModelCopy.failure(config.modelName, e))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    TestState.Failed(DecisionModelCopy.failure(config.modelName, e))
                                }
                            }
                        },
                        Modifier.testTag("decision-test"), enabled = test != TestState.Running,
                    ) { Text(stringResource(if (test == TestState.Running) R.string.decision_testing else R.string.decision_test)) }
                }
                when (val t = test) {
                    TestState.Passed -> Text(
                        stringResource(R.string.decision_test_ok, config.modelName), Modifier.testTag("decision-test-result"),
                        style = MikaType.Hint, color = c.ok,
                    )
                    is TestState.Failed -> Text(t.message, Modifier.testTag("decision-test-result"), style = MikaType.Hint, color = c.bad)
                    else -> {}
                }
                // 数据去向:委托的每一步都把目标和屏幕文字发给这一家
                Text(
                    stringResource(R.string.decision_privacy), Modifier.padding(top = 8.dp).testTag("decision-privacy"),
                    style = MikaType.Hint, color = c.ink3,
                )
            }
        },
        confirmButton = { TextButton({ onSave(config) }, Modifier.testTag("decision-save")) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
