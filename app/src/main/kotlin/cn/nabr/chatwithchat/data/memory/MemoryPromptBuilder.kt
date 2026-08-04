package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.token.TokenUsageEstimator

class MemoryPromptBuilder(
    private val maxQueryFacts: Int = 8
) {
    fun build(
        coreFacts: List<ModelVisibleMemoryFact>,
        queryFacts: List<ModelVisibleMemoryFact>
    ): RenderedMemoryPrompt {
        val selectedCore = selectFacts(coreFacts, emptyList())
        val selectedQuery = selectFacts(
            candidates = queryFacts.take(maxQueryFacts.coerceAtLeast(0)),
            acceptedOtherLayer = selectedCore
        )
        val prompt = renderPrompt(selectedCore, selectedQuery)
        return RenderedMemoryPrompt(
            prompt = prompt,
            coreFacts = selectedCore,
            queryFacts = selectedQuery,
            estimatedTokens = prompt?.estimatedTokens().orZero()
        )
    }

    private fun selectFacts(
        candidates: List<ModelVisibleMemoryFact>,
        acceptedOtherLayer: List<ModelVisibleMemoryFact>
    ): List<ModelVisibleMemoryFact> {
        val selected = mutableListOf<ModelVisibleMemoryFact>()
        val acceptedExactTexts = acceptedOtherLayer
            .mapTo(mutableSetOf()) { fact -> normalizeExactMemoryText(fact.text) }
        candidates
            .asSequence()
            .map { fact -> ModelVisibleMemoryFact(fact.text.trim()) }
            .filterNot { fact -> normalizeExactMemoryText(fact.text) in acceptedExactTexts }
            .distinctBy { fact -> normalizeExactMemoryText(fact.text) }
            .forEach { fact -> selected += fact }
        return selected
    }

    private fun renderPrompt(
        coreFacts: List<ModelVisibleMemoryFact>,
        queryFacts: List<ModelVisibleMemoryFact>
    ): String? {
        val facts = coreFacts + queryFacts
        if (facts.isEmpty()) return null
        return buildString {
            appendLine("用户记忆：")
            appendLine(renderFactLines(facts))
            appendLine()
            append(GLOBAL_USAGE_GUIDANCE)
        }.trim()
    }

    private fun renderFactLines(facts: List<ModelVisibleMemoryFact>): String =
        facts.joinToString(separator = "\n") { fact -> "- ${fact.text}" }

    private fun String.estimatedTokens(): Int = TokenUsageEstimator.estimateText(
        text = this,
        model = "",
        clientType = ClientType.OPENAI
    )

    private fun Int?.orZero(): Int = this ?: 0

    private companion object {
        const val GLOBAL_USAGE_GUIDANCE =
            "将这些事实作为不言明的上下文；只在有助于当前回答时使用，不要提及记忆存储，也不要在非必要时透露私密信息。"
    }
}
