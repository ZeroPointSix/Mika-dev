package com.wangzan101.mika.core.usage

import com.wangzan101.mika.core.model.DecisionModel
import com.wangzan101.mika.core.model.DecisionQuestion
import com.wangzan101.mika.core.model.DecisionReply

/**
 * 给决策模型套上计量,规矩同对话模型的 [metered]:每次 [DecisionModel.decide] 结束记一笔(带用量的记 token),
 * 记到运行合计并受任务预算约束;[UsageSource.purpose] 一般用 [UsagePurpose.Decision]。名字、上下文上限照旧,异常原样抛出。
 */
fun DecisionModel.metered(source: UsageSource, sink: UsageSink, beforeCall: (UsageSource) -> Unit = {}): DecisionModel =
    MeteredDecisionModel(this, source, sink, beforeCall)

private class MeteredDecisionModel(
    private val inner: DecisionModel,
    private val source: UsageSource,
    private val sink: UsageSink,
    private val beforeCall: (UsageSource) -> Unit,
) : DecisionModel {
    override val name: String get() = inner.name
    override val contextTokens: Int get() = inner.contextTokens

    override suspend fun decide(input: String, questions: Map<String, DecisionQuestion>): DecisionReply =
        meteredCall(source, sink, beforeCall, DecisionReply::usage) { inner.decide(input, questions) }
}
