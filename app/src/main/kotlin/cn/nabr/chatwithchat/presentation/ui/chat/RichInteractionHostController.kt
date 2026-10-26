package cn.nabr.chatwithchat.presentation.ui.chat

import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.resetActiveRevision
import cn.nabr.chatwithchat.data.richcontent.RichContentParser
import cn.nabr.chatwithchat.data.richcontent.RichDocument
import cn.nabr.chatwithchat.data.richcontent.RichNode
import cn.nabr.chatwithchat.data.richcontent.SourceRange
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionEvent
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionIdentity
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionResult
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionRuntime
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionSessionState
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionSnapshot
import cn.nabr.chatwithchat.data.richcontent.openui.OpenUiEnvelope
import cn.nabr.chatwithchat.util.StreamTerminalOutcome
import java.security.MessageDigest
import kotlinx.coroutines.flow.StateFlow

data class RichInteractionHostBinding(
    val identity: RichInteractionIdentity,
    val document: RichDocument,
    val session: StateFlow<RichInteractionSessionState>
)

/** A source resolved from the host's current message list, never from a component event. */
internal data class RichInteractionHostSource(
    val chatId: Int,
    val turnKey: String,
    val platformUid: String,
    val revisionKey: String,
    val revisionFingerprint: String,
    val content: String,
    val isLatestRevision: Boolean = true,
    val isStreaming: Boolean = false
) {
    val slot: String get() = "$chatId:$turnKey:$platformUid"
}

internal data class RichInteractionDraftGate(
    val isBusy: Boolean = false,
    val isPersisting: Boolean = false,
    val isEditing: Boolean = false,
    val hasDraftText: Boolean = false,
    val hasAttachments: Boolean = false,
    val isImportingAttachments: Boolean = false,
    val hasQueuedQuestion: Boolean = false
) {
    fun rejectionReason(forAction: Boolean): String? = when {
        isPersisting -> "saving"
        isBusy || hasQueuedQuestion -> "busy"
        isEditing -> "editing"
        forAction && hasDraftText -> "existing-draft"
        forAction && hasAttachments -> "existing-attachments"
        forAction && isImportingAttachments -> "importing-attachments"
        else -> null
    }
}

/** Bounded, screen-owned evidence. A database row alone is deliberately not completion evidence. */
internal class RichInteractionHostController(
    private val runtime: RichInteractionRuntime,
    private val parser: RichContentParser,
    private val maxSources: Int = 32,
    private val maxSourceChars: Int = 1048576
) {
    private data class Attempt(
        val id: String,
        val generation: Long
    )

    private data class CachedBinding(
        val source: RichInteractionHostSource,
        val allowed: Boolean,
        val binding: RichInteractionHostBinding
    )

    private val attempts = LinkedHashMap<String, Attempt>()
    private data class SuccessKey(val slot: String, val revisionKey: String, val fingerprint: String)
    private val successes = LinkedHashMap<SuccessKey, Attempt>()
    private val bindings = LinkedHashMap<RichInteractionIdentity, CachedBinding>(16, 0.75f, true)
    private var nextGeneration = 0L

    init {
        require(maxSources > 0 && maxSourceChars > 0)
    }

    fun beginAttempt(source: RichInteractionHostSource, attemptId: String) {
        if (attempts[source.slot]?.id == attemptId) return
        val prior = attempts.entries.firstOrNull { it.value.id == attemptId }
        if (prior != null) {
            val attempt = prior.value
            invalidate(prior.key)
            attempts[source.slot] = attempt
            return
        }
        suspendSlot(source.slot)
        attempts[source.slot] = Attempt(attemptId, ++nextGeneration)
        while (attempts.size > maxSources) invalidate(attempts.keys.first())
    }

    fun completeAttempt(
        source: RichInteractionHostSource,
        attemptId: String,
        outcome: StreamTerminalOutcome,
        saveSucceeded: Boolean
    ) {
        val oldSlot = attempts.entries.firstOrNull { it.value.id == attemptId }?.key ?: return
        val attempt = attempts[oldSlot] ?: return
        if (attempt.id != attemptId) return
        if (oldSlot != source.slot) {
            // Unsaved user rows receive their database ids at this save. No live controls exist yet.
            invalidate(oldSlot)
            attempts[source.slot] = attempt
        }
        if (saveSucceeded && outcome == StreamTerminalOutcome.Success && source.isLatestRevision) {
            successes[successKey(source)] = attempt
            while (successes.size > maxSources) {
                val evicted = successes.keys.first()
                successes.remove(evicted)
                suspendSlot(evicted.slot)
            }
        }
    }

    /** snapshotLatestAssistantRevision assigns a fresh timestamp, so archive that exact signature. */
    fun archiveRevision(source: RichInteractionHostSource, archivedFingerprint: String) {
        val success = successes[successKey(source)] ?: return
        successes[successKey(source).copy(fingerprint = archivedFingerprint)] = success
        while (successes.size > maxSources) successes.remove(successes.keys.first())
    }

    fun invalidateLatest(source: RichInteractionHostSource) {
        suspendSlot(source.slot)
        attempts.remove(source.slot)
    }

    /** Invalidates captured callbacks while preserving local values of archived successful versions. */
    private fun suspendSlot(slot: String) {
        val keys = bindings.filterValues { it.source.slot == slot }.keys.toList()
        keys.forEach { key ->
            bindings.remove(key)?.let { cached -> runtime.sync(key, cached.binding.document, interactionAllowed = false) }
        }
    }

    fun bind(source: RichInteractionHostSource, gate: RichInteractionDraftGate): RichInteractionHostBinding {
        val attempt = evidence(source) ?: attempts[source.slot]
        val identity = identity(source, attempt)
        val allowed = isSavedSuccess(source) && gate.rejectionReason(forAction = false) == null
        val cached = bindings[identity]
        if (cached != null && cached.source == source && cached.allowed == allowed && runtime.observe(identity) != null) {
            return cached.binding
        }
        val document = if (source.content.length > maxSourceChars) {
            RichDocument(
                source = source.content,
                nodes = listOf(RichNode.Markdown(source.content, SourceRange(0, source.content.length))),
                diagnostics = emptyList(),
                generation = attempt?.generation ?: 0,
                isStreaming = source.isStreaming
            )
        } else {
            parser.parse(source.content, source.isStreaming, attempt?.generation ?: 0)
        }
        runtime.sync(identity, document, interactionAllowed = allowed)
        val binding = RichInteractionHostBinding(identity, document, checkNotNull(runtime.observe(identity)))
        bindings[identity] = CachedBinding(source, allowed, binding)
        while (bindings.size > maxSources) {
            val evicted = bindings.keys.first()
            bindings.remove(evicted)
            runtime.reset(evicted)
        }
        return binding
    }

    fun dispatch(
        identity: RichInteractionIdentity,
        event: RichInteractionEvent,
        selectedSource: RichInteractionHostSource?,
        gate: RichInteractionDraftGate
    ): RichInteractionResult {
        val source = selectedSource ?: return rejected("missing-source")
        val attempt = evidence(source) ?: attempts[source.slot]
        if (identity != identity(source, attempt) || !isSavedSuccess(source)) return rejected("stale-source")
        gate.rejectionReason(event is RichInteractionEvent.InvokeAction)?.let { return rejected(it) }
        // Rebind before dispatch so a source change or rollback invalidates the old token immediately.
        bind(source, gate)
        return runtime.dispatch(identity, event)
    }

    fun canAccept(
        snapshot: RichInteractionSnapshot,
        selectedSource: RichInteractionHostSource?,
        gate: RichInteractionDraftGate
    ): Boolean {
        val source = selectedSource ?: return false
        val attempt = evidence(source) ?: attempts[source.slot]
        if (gate.rejectionReason(forAction = true) != null ||
            !isSavedSuccess(source) ||
            snapshot.identity != identity(source, attempt) ||
            !runtime.isCurrent(snapshot)
        ) {
            return false
        }
        val current = runtime.observe(snapshot.identity)?.value ?: return false
        return snapshot.values.all { (key, value) -> current.values[key] == value }
    }

    fun bindOpenUi(source: RichInteractionHostSource, gate: RichInteractionDraftGate): OpenUiHostBinding {
        val attempt = evidence(source) ?: attempts[source.slot]
        return OpenUiHostBinding(
            identity = identity(source, attempt).toString(),
            revision = source.revisionFingerprint,
            interactive = source.isLatestRevision && isSavedSuccess(source) && gate.rejectionReason(false) == null
        )
    }

    fun canAcceptOpenUi(event: OpenUiHostAction, source: RichInteractionHostSource?, gate: RichInteractionDraftGate): Boolean {
        if (source == null || !source.isLatestRevision || source.content.length > maxSourceChars || !OpenUiActionPolicy.accepts(event)) return false
        val binding = bindOpenUi(source, gate)
        if (!binding.interactive || event.identity != binding.identity || event.revision != binding.revision) return false
        if (gate.rejectionReason(event.action == "ToAssistant") != null) return false
        val envelope = OpenUiEnvelope.parse(source.content)
        return envelope.source != null && envelope.isComplete
    }

    fun invalidate(slot: String) {
        attempts.remove(slot)
        successes.keys.filter { it.slot == slot }.forEach(successes::remove)
        val keys = bindings.filterValues { it.source.slot == slot }.keys.toList()
        keys.forEach {
            bindings.remove(it)
            runtime.reset(it)
        }
    }

    fun clear() {
        attempts.clear()
        successes.clear()
        bindings.clear()
        runtime.clear()
    }

    private fun evidence(source: RichInteractionHostSource): Attempt? {
        val success = successes[successKey(source)] ?: return null
        return success.takeIf { !source.isLatestRevision || attempts[source.slot]?.id == success.id }
    }

    private fun isSavedSuccess(source: RichInteractionHostSource): Boolean =
        !source.isStreaming && evidence(source) != null

    private fun successKey(source: RichInteractionHostSource) = SuccessKey(
        source.slot,
        source.revisionKey,
        source.revisionFingerprint
    )

    private fun identity(source: RichInteractionHostSource, attempt: Attempt?) = RichInteractionIdentity(
        chatId = source.chatId,
        turnKey = source.turnKey,
        platformUid = source.platformUid,
        revisionKey = if (evidence(source) != null) {
            "${source.revisionKey}:attempt:${attempt?.id}"
        } else {
            "${source.revisionKey}:attempt:${attempt?.id ?: "unknown"}"
        }
    )

    private fun rejected(reason: String) = RichInteractionResult.Rejected(reason)
}

internal fun richInteractionRevisionFingerprint(content: String, thoughts: String, createdAt: Long): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$createdAt:${content.length}:$content${thoughts.length}:$thoughts".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

internal data class RichInteractionRevisionSource(
    val key: String,
    val fingerprint: String,
    val content: String,
    val isLatest: Boolean
)

/** Ordinals survive prepending history, even when two versions share content and timestamp. */
internal fun MessageV2.richInteractionRevisionSource(useLatestRevision: Boolean = false): RichInteractionRevisionSource {
    val message = if (useLatestRevision) resetActiveRevision() else this
    val revision = message.revisions.getOrNull(message.activeRevisionIndex)
    val content = revision?.content ?: message.content
    val ordinal = if (revision == null) message.revisions.size else message.revisions.lastIndex - message.activeRevisionIndex
    return RichInteractionRevisionSource(
        key = "revision:$ordinal",
        fingerprint = richInteractionRevisionFingerprint(
            content,
            revision?.thoughts ?: message.thoughts,
            revision?.createdAt ?: message.createdAt
        ),
        content = content,
        isLatest = revision == null
    )
}
