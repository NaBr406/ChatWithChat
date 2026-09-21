package cn.nabr.chatwithchat.data.tool

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class AskUserQuestionBroker @Inject constructor() {
    data class PendingQuestion(
        val requestId: String,
        val chatId: Int,
        val turnKey: String?,
        val callId: String,
        val questions: List<AskUserQuestion>
    )

    sealed interface Decision {
        data class Answered(val answers: List<AskUserQuestionAnswer>) : Decision
        data object Cancelled : Decision
    }

    private data class ActiveQuestion(
        val pending: PendingQuestion,
        val decision: CompletableDeferred<Decision>
    )

    private val lock = Any()
    private val queue = ArrayDeque<ActiveQuestion>()

    private val _queued = MutableStateFlow<List<PendingQuestion>>(emptyList())
    val queued = _queued.asStateFlow()

    suspend fun awaitAnswer(
        call: ToolCall,
        questions: List<AskUserQuestion>,
        chatId: Int = 0,
        turnKey: String? = null
    ): Decision {
        val active = ActiveQuestion(
            pending = PendingQuestion(
                requestId = UUID.randomUUID().toString(),
                chatId = chatId,
                turnKey = turnKey,
                callId = call.id,
                questions = questions
            ),
            decision = CompletableDeferred()
        )
        synchronized(lock) {
            queue.addLast(active)
            publishPendingLocked()
        }
        return try {
            active.decision.await()
        } finally {
            synchronized(lock) {
                queue.remove(active)
                publishPendingLocked()
            }
        }
    }

    fun submit(requestId: String, answers: List<AskUserQuestionAnswer>): Boolean {
        val resolved = synchronized(lock) {
            val active = queue.firstOrNull { pending -> pending.pending.requestId == requestId }
                ?: return false
            val normalizedAnswers = normalizeAnswers(active.pending.questions, answers)
                ?: return false
            queue.remove(active)
            publishPendingLocked()
            active to normalizedAnswers
        }
        return resolved.first.decision.complete(Decision.Answered(resolved.second))
    }

    fun cancel(requestId: String): Boolean {
        val active = synchronized(lock) {
            val current = queue.firstOrNull { pending -> pending.pending.requestId == requestId }
                ?: return false
            queue.remove(current)
            publishPendingLocked()
            current
        }
        return active.decision.complete(Decision.Cancelled)
    }

    private fun publishPendingLocked() {
        _queued.value = queue.map { active -> active.pending }
    }
}

internal fun normalizeAnswers(
    questions: List<AskUserQuestion>,
    answers: List<AskUserQuestionAnswer>
): List<AskUserQuestionAnswer>? = runCatching {
    require(answers.size == questions.size) { "answer_count_mismatch" }
    val answersByHeader = answers.groupBy { answer -> answer.header.normalizedHeader() }
    require(answersByHeader.values.all { grouped -> grouped.size == 1 }) { "answer_header_duplicate" }

    questions.map { question ->
        val answer = answersByHeader[question.header.normalizedHeader()]?.singleOrNull()
            ?: error("answer_missing")
        val optionLabels = question.options.map { option -> option.label }
        val selectedOptions = answer.selectedOptions
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        require(selectedOptions.all { selected -> selected in optionLabels }) { "answer_option_invalid" }
        require(question.multiSelect || selectedOptions.size <= 1) { "answer_single_select_invalid" }
        val otherText = answer.otherText?.trim()?.takeIf(String::isNotEmpty)
        require((otherText?.length ?: 0) <= MAX_OTHER_TEXT_CHARS) { "answer_other_too_long" }
        require(question.multiSelect || selectedOptions.isEmpty() || otherText == null) {
            "answer_single_select_other_conflict"
        }
        require(selectedOptions.isNotEmpty() || otherText != null) { "answer_required" }
        AskUserQuestionAnswer(
            header = question.header,
            selectedOptions = selectedOptions,
            otherText = otherText
        )
    }
}.getOrNull()
