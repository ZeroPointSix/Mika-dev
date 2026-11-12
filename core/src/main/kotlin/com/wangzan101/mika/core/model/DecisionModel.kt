package com.wangzan101.mika.core.model

/**
 * 决策模型:不生成文字、不调工具,只对一份输入回答若干带类型的问题,给出选项、概率分布和置信度。
 * 和 [ChatModel] 并列;接口不绑定服务商,各家的写法由适配器转换。失败时抛 [ModelError]。
 */
interface DecisionModel {
    /** 模型名,只用于记录。 */
    val name: String get() = ""

    /** 一次请求(输入加全部问题)能放多少 token。 */
    val contextTokens: Int

    /**
     * [input] 是给模型看的状态文字;[questions] 的键是问题名(`[A-Za-z0-9_-]{1,64}`,模型看不到),各题互相看不到。
     * 回复里每个问题名都有一条答案,类型和提问的一致;缺了或类型不符由适配器抛 [ModelError.Server]。
     */
    suspend fun decide(input: String, questions: Map<String, DecisionQuestion>): DecisionReply
}

sealed interface DecisionQuestion {
    val instructions: String

    /** 是否题。 */
    data class Noul(
        override val instructions: String,
        val trueDescription: String? = null,
        val falseDescription: String? = null,
    ) : DecisionQuestion

    /** 多选一。[options] 是选项名(`[A-Za-z0-9_-]{1,64}`)到说明,2–255 项;服务商可能按选项名排序后才给模型。 */
    data class Choice(override val instructions: String, val options: Map<String, String>) : DecisionQuestion

    /** 打分。[levels] 由低到高,2–255 级。 */
    data class Score(override val instructions: String, val levels: List<String>) : DecisionQuestion
}

sealed interface DecisionAnswer {
    /** 最可能那个答案的概率,0–1。 */
    val confidence: Double

    /** [probability] 是「是」的概率。 */
    data class Noul(val probability: Double, override val confidence: Double) : DecisionAnswer

    /** [choice] 是选中的选项名;[probabilities] 是各选项名的概率。 */
    data class Choice(
        val choice: String,
        val probabilities: Map<String, Double>,
        override val confidence: Double,
    ) : DecisionAnswer

    /** [level] 是概率最高那一级的下标(从 0 起);[probabilities] 按级别下标排。 */
    data class Score(
        val level: Int,
        val probabilities: List<Double>,
        override val confidence: Double,
    ) : DecisionAnswer
}

data class DecisionReply(val answers: Map<String, DecisionAnswer>, val usage: Usage?)
