package cn.nabr.chatwithchat.data.repository

import cn.nabr.chatwithchat.data.dto.anthropic.common.MessageRole
import cn.nabr.chatwithchat.data.dto.anthropic.common.TextContent
import cn.nabr.chatwithchat.data.dto.anthropic.request.InputMessage
import cn.nabr.chatwithchat.data.dto.anthropic.request.MessageRequest
import cn.nabr.chatwithchat.data.dto.google.common.Content
import cn.nabr.chatwithchat.data.dto.google.common.Part
import cn.nabr.chatwithchat.data.dto.google.request.GenerateContentRequest
import cn.nabr.chatwithchat.data.dto.groq.request.GroqChatCompletionRequest
import cn.nabr.chatwithchat.data.dto.openai.common.Role
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionRequest
import cn.nabr.chatwithchat.data.dto.openai.request.ChatMessage
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseInputContent
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseInputMessage
import cn.nabr.chatwithchat.data.dto.openai.request.ResponsesRequest

// These requests finish an already-started answer. Keep prior tool results in history, but do not
// offer tools again: a continuation must not repeat actions that have already been executed.
internal fun ResponsesRequest.continueOutput(text: String): ResponsesRequest = copy(
    input = input + listOf(
        ResponseInputMessage("assistant", ResponseInputContent.text(text)),
        ResponseInputMessage("user", ResponseInputContent.text(OUTPUT_CONTINUATION_INSTRUCTION))
    ),
    tools = null,
    toolChoice = null
)

internal fun ChatCompletionRequest.continueOutput(text: String, thoughts: String): ChatCompletionRequest = copy(
    messages = messages + continuationChatMessages(text, thoughts),
    tools = null,
    toolChoice = null
)

internal fun GroqChatCompletionRequest.continueOutput(text: String, thoughts: String): GroqChatCompletionRequest = copy(
    messages = messages + continuationChatMessages(text, thoughts)
)

private fun continuationChatMessages(text: String, thoughts: String): List<ChatMessage> = listOf(
    ChatMessage(Role.ASSISTANT, contentText = text, reasoningContent = thoughts.takeIf { it.isNotEmpty() }),
    ChatMessage(Role.USER, contentText = OUTPUT_CONTINUATION_INSTRUCTION)
)

internal fun MessageRequest.continueOutput(text: String): MessageRequest = copy(
    messages = messages + listOf(
        InputMessage(MessageRole.ASSISTANT, listOf(TextContent(text))),
        InputMessage(MessageRole.USER, listOf(TextContent(OUTPUT_CONTINUATION_INSTRUCTION)))
    ),
    tools = null,
    toolChoice = null
)

internal fun GenerateContentRequest.continueOutput(text: String): GenerateContentRequest = copy(
    contents = contents + listOf(
        Content(cn.nabr.chatwithchat.data.dto.google.common.Role.MODEL, listOf(Part.text(text))),
        Content(cn.nabr.chatwithchat.data.dto.google.common.Role.USER, listOf(Part.text(OUTPUT_CONTINUATION_INSTRUCTION)))
    ),
    tools = null,
    toolConfig = null
)
