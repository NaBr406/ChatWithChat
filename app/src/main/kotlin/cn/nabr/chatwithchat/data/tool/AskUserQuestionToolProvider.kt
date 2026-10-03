package cn.nabr.chatwithchat.data.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AskUserQuestionToolProvider(
    private val broker: AskUserQuestionBroker
) : ToolProvider {
    override val definition: ToolDefinition = ToolDefinition.AskUserQuestion

    override val settingsMetadata: ToolSettingsMetadata = ToolSettingsMetadata(
        userVisible = false,
        category = ToolCategory.Utility,
        defaultEnabled = true,
        isSensitive = false,
        presentationKey = "ask_user_question",
        iconKey = "help"
    )

    override val discoveryMetadata: ToolDiscoveryMetadata = ToolDiscoveryMetadata(
        exposure = ToolExposure.Resident,
        priority = 30
    )

    override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy(
        effect = ToolEffect.INTERNAL_STATE,
        approvalPolicy = ToolApprovalPolicy.NOT_REQUIRED
    )

    override val interactionMode: ToolInteractionMode = ToolInteractionMode.ForegroundRequired

    override val policy: ToolPolicy = ToolPolicy(
        maxCallsPerRequest = 1,
        maxCallsPerChat = 4,
        maxResultChars = 12_000
    )

    override fun progressLabel(call: ToolCall): String = call.parseAskUserQuestions()
        .getOrNull()
        ?.firstOrNull()
        ?.header
        ?: definition.name

    override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult =
        executeInternal(call, chatId = 0, turnKey = null)

    override suspend fun execute(
        call: ToolCall,
        config: ToolLoopConfig,
        executionContext: ToolExecutionContext
    ): ToolResult = executeInternal(
        call = call,
        chatId = executionContext.chatId,
        turnKey = executionContext.turnKey
    )

    private suspend fun executeInternal(
        call: ToolCall,
        chatId: Int,
        turnKey: String?
    ): ToolResult {
        val questions = call.parseAskUserQuestions().getOrElse { throwable ->
            return call.errorResult(
                message = "tool_arguments_invalid:${throwable.message ?: "invalid_arguments"}",
                errorCode = "tool_arguments_invalid"
            )
        }
        return when (val decision = broker.awaitAnswer(call, questions, chatId, turnKey)) {
            is AskUserQuestionBroker.Decision.Answered -> call.answeredResult(questions, decision.answers)
            AskUserQuestionBroker.Decision.Cancelled -> call.cancelledResult()
        }
    }
}

private fun ToolCall.answeredResult(
    questions: List<AskUserQuestion>,
    answers: List<AskUserQuestionAnswer>
): ToolResult {
    val answersByHeader = answers.associateBy { answer -> answer.header.normalizedHeader() }
    val payload = buildJsonObject {
        put("status", "answered")
        put(
            "answers",
            JsonArray(
                questions.map { question ->
                    val answer = answersByHeader.getValue(question.header.normalizedHeader())
                    buildJsonObject {
                        put("header", question.header)
                        put("question", question.question)
                        put("selectedOptions", JsonArray(answer.selectedOptions.map(::JsonPrimitive)))
                        answer.otherText?.let { otherText -> put("otherText", otherText) }
                    }
                }
            )
        )
    }
    return ToolResult(
        callId = id,
        name = name,
        content = payload.toString(),
        metadata = mapOf("status" to "answered"),
        structuredContent = payload
    )
}

private fun ToolCall.cancelledResult(): ToolResult {
    val payload = buildJsonObject {
        put("status", "cancelled")
        put("reason", "user_dismissed")
    }
    return ToolResult(
        callId = id,
        name = name,
        content = payload.toString(),
        metadata = mapOf("status" to "cancelled"),
        structuredContent = payload
    )
}
