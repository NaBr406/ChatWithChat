package cn.nabr.chatwithchat.data.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import java.util.Locale

data class AskUserQuestion(
    val header: String,
    val question: String,
    val options: List<AskUserQuestionOption>,
    val multiSelect: Boolean = false
)

data class AskUserQuestionOption(
    val label: String,
    val description: String
)

data class AskUserQuestionAnswer(
    val header: String,
    val selectedOptions: List<String> = emptyList(),
    val otherText: String? = null
)

internal fun ToolCall.parseAskUserQuestions(): Result<List<AskUserQuestion>> = runCatching {
    val arguments = argumentsObject().getOrThrow()
    val questions = arguments["questions"]?.jsonArray ?: error("questions_required")
    require(questions.size in MIN_QUESTIONS..MAX_QUESTIONS) { "question_count_out_of_range" }

    val parsed = questions.mapIndexed { index, element ->
        val question = element as? JsonObject ?: error("question_${index}_object_required")
        val header = question.requiredText("header", MAX_HEADER_CHARS)
        val text = question.requiredText("question", MAX_QUESTION_CHARS)
        val options = question["options"]?.jsonArray ?: error("question_${index}_options_required")
        require(options.size in MIN_OPTIONS..MAX_OPTIONS) { "option_count_out_of_range" }
        val parsedOptions = options.mapIndexed { optionIndex, optionElement ->
            val option = optionElement as? JsonObject
                ?: error("question_${index}_option_${optionIndex}_object_required")
            AskUserQuestionOption(
                label = option.requiredText("label", MAX_LABEL_CHARS),
                description = option.requiredText("description", MAX_DESCRIPTION_CHARS)
            )
        }
        val duplicateLabels = parsedOptions
            .groupingBy { option -> option.label.normalizedHeader() }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        require(duplicateLabels.isEmpty()) { "option_labels_duplicate" }
        AskUserQuestion(
            header = header,
            question = text,
            options = parsedOptions,
            multiSelect = question.optionalBoolean("multiSelect") ?: false
        )
    }

    val duplicateHeaders = parsed
        .groupingBy { question -> question.header.normalizedHeader() }
        .eachCount()
        .filterValues { count -> count > 1 }
        .keys
    require(duplicateHeaders.isEmpty()) { "question_headers_duplicate" }
    parsed
}

private fun JsonObject.requiredText(name: String, maxChars: Int): String {
    val primitive = this[name] as? JsonPrimitive ?: error("${name}_required")
    require(primitive.isString) { "${name}_string_required" }
    val value = primitive.content.trim()
    require(value.isNotEmpty()) { "${name}_required" }
    require(value.length <= maxChars) { "${name}_too_long" }
    return value
}

private fun JsonObject.optionalBoolean(name: String): Boolean? {
    val element = this[name] ?: return null
    val primitive = element as? JsonPrimitive ?: error("${name}_boolean_required")
    return primitive.booleanOrNull ?: error("${name}_boolean_required")
}

internal fun String.normalizedHeader(): String = trim().lowercase(Locale.ROOT)

internal const val MIN_QUESTIONS = 1
internal const val MAX_QUESTIONS = 4
internal const val MIN_OPTIONS = 2
internal const val MAX_OPTIONS = 4
internal const val MAX_HEADER_CHARS = 24
internal const val MAX_QUESTION_CHARS = 600
internal const val MAX_LABEL_CHARS = 80
internal const val MAX_DESCRIPTION_CHARS = 240
internal const val MAX_OTHER_TEXT_CHARS = 2_000
