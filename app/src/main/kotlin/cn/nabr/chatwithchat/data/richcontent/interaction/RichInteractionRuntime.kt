package cn.nabr.chatwithchat.data.richcontent.interaction

import cn.nabr.chatwithchat.data.richcontent.RichDocument
import cn.nabr.chatwithchat.data.richcontent.RichNode
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory local state. Own this in a ViewModel, never in an individual rendered component. */
class RichInteractionRuntime(
    private val registry: RichInteractionRegistry = RichInteractionRegistry(),
    private val limits: RichInteractionLimits = RichInteractionLimits()
) {
    private class Session(val state: MutableStateFlow<RichInteractionSessionState>) {
        var source: String? = null
        var documentGeneration: Long? = null
        val events = mutableSetOf<String>()
        val issuedSnapshots = mutableMapOf<String, String>()
    }

    private val sessions = LinkedHashMap<RichInteractionIdentity, Session>(16, 0.75f, true)
    private var nextIncarnation = 0L
    private val keyPattern = Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}")

    @Synchronized
    fun observe(identity: RichInteractionIdentity): StateFlow<RichInteractionSessionState>? =
        sessions[identity]?.state?.asStateFlow()

    @Synchronized
    fun sync(
        identity: RichInteractionIdentity,
        document: RichDocument,
        interactionAllowed: Boolean
    ): RichInteractionSessionState {
        val session = sessions[identity] ?: createSession(identity)
        val previous = session.state.value
        val source = document.source
        val reset = session.source == null ||
            session.documentGeneration != document.generation ||
            !source.startsWith(session.source.orEmpty())
        val declarations = if (source.length > limits.maxSourceChars) {
            Declarations(reason = "source-limit")
        } else {
            declarations(document)
        }
        val enabled = interactionAllowed &&
            !document.isStreaming &&
            declarations.reason == null &&
            (declarations.fields.isNotEmpty() || declarations.actions.isNotEmpty())
        val invalidate = reset ||
            previous.isInteractionEnabled != enabled ||
            previous.rejectionReason != declarations.reason ||
            previous.fields != declarations.fields ||
            previous.actions != declarations.actions
        val token = if (invalidate) {
            session.events.clear()
            session.issuedSnapshots.clear()
            previous.token.copy(generation = previous.token.generation + 1)
        } else {
            previous.token
        }
        val values = declarations.fields.mapValues { (key, field) ->
            if (!reset && previous.fields[key] == field) previous.values[key] ?: field.initialValue else field.initialValue
        }
        session.source = source.takeIf { it.length <= limits.maxSourceChars }
        session.documentGeneration = document.generation
        val state = RichInteractionSessionState(
            identity = identity,
            token = token,
            fields = immutableMap(declarations.fields),
            actions = immutableMap(declarations.actions),
            values = immutableMap(values),
            isInteractionEnabled = enabled,
            documentGeneration = document.generation,
            sourceFingerprint = if (source.length <= limits.maxSourceChars) fingerprint(source) else "",
            rejectionReason = declarations.reason
        )
        session.state.value = state
        return state
    }

    @Synchronized
    fun dispatch(identity: RichInteractionIdentity, event: RichInteractionEvent): RichInteractionResult {
        val session = sessions[identity] ?: return rejected("missing-session")
        val state = session.state.value
        if (event.token != state.token) return rejected("stale-token")
        if (!state.isInteractionEnabled) return rejected("interaction-disabled")
        if (event.eventId.isBlank() || event.eventId.length > 128) return rejected("invalid-event-id")
        if (event.eventId in session.events) return rejected("duplicate-event")
        // Never forget a dispatched ID within an epoch: exhausting the bounded ledger fails closed.
        if (session.events.size >= limits.maxEventsPerGeneration) return rejected("event-limit")
        return when (event) {
            is RichInteractionEvent.SetValue -> {
                val field = state.fields[event.key] ?: return rejected("unknown-field")
                if (!accepts(field.type, event.value)) return rejected("invalid-value")
                val values = state.values + (event.key to freezeValue(event.value))
                if (values.values.sumOf(::valueChars) > limits.maxSessionValueChars) return rejected("state-limit")
                val updated = state.copy(values = immutableMap(values))
                session.events += event.eventId
                session.state.value = updated
                RichInteractionResult.Updated(updated)
            }
            is RichInteractionEvent.InvokeAction -> {
                val action = state.actions[event.key] ?: return rejected("unknown-action")
                val values = linkedMapOf<String, RichInteractionValue>()
                for (ref in action.refs) {
                    if (state.fields[ref]?.exportable != true) return rejected("invalid-reference")
                    values[ref] = state.values[ref] ?: return rejected("missing-value")
                }
                val text = buildString {
                    append(action.prompt)
                    if (values.isNotEmpty()) {
                        append("\n\nReferenced local values (JSON):\n{")
                        append(values.entries.joinToString(",") { (key, value) -> "${json(key)}:${valueText(value)}" })
                        append('}')
                    }
                }
                if (text.length > limits.maxSnapshotChars) return rejected("snapshot-limit")
                val snapshot = RichInteractionSnapshot(
                    identity = identity,
                    token = state.token,
                    documentGeneration = state.documentGeneration ?: return rejected("missing-document"),
                    sourceFingerprint = state.sourceFingerprint,
                    actionKey = action.key,
                    sourceLabel = action.sourceLabel,
                    prompt = action.prompt,
                    values = immutableMap(values),
                    text = text,
                    eventId = event.eventId
                )
                session.events += event.eventId
                session.issuedSnapshots[event.eventId] = snapshotFingerprint(snapshot)
                RichInteractionResult.Action(snapshot)
            }
        }
    }

    /** Hosts can recheck this immediately before accepting a queued snapshot. */
    @Synchronized
    fun isCurrent(snapshot: RichInteractionSnapshot): Boolean {
        val session = sessions[snapshot.identity] ?: return false
        val state = session.state.value
        return state.isInteractionEnabled &&
            state.token == snapshot.token &&
            state.documentGeneration == snapshot.documentGeneration &&
            state.sourceFingerprint == snapshot.sourceFingerprint &&
            session.issuedSnapshots[snapshot.eventId] == snapshotFingerprint(snapshot) &&
            state.actions[snapshot.actionKey]?.let {
                it.prompt == snapshot.prompt && it.sourceLabel == snapshot.sourceLabel && it.refs.toSet() == snapshot.values.keys
            } == true
    }

    @Synchronized
    fun reset(identity: RichInteractionIdentity) {
        sessions[identity]?.let { invalidate(it) }
    }

    @Synchronized
    fun clear() {
        sessions.values.forEach(::invalidate)
        sessions.clear()
    }

    private fun createSession(identity: RichInteractionIdentity): Session {
        val session = Session(MutableStateFlow(RichInteractionSessionState(identity, RichInteractionToken(++nextIncarnation, 0))))
        sessions[identity] = session
        if (sessions.size > limits.maxSessions) {
            val iterator = sessions.entries.iterator()
            val evicted = iterator.next().value
            iterator.remove()
            invalidate(evicted)
        }
        return session
    }

    private fun invalidate(session: Session) {
        session.source = null
        session.documentGeneration = null
        session.events.clear()
        session.issuedSnapshots.clear()
        val previous = session.state.value
        session.state.value = RichInteractionSessionState(
            identity = previous.identity,
            token = previous.token.copy(generation = previous.token.generation + 1)
        )
    }

    private data class Declarations(
        val fields: Map<String, RichInteractionField> = emptyMap(),
        val actions: Map<String, RichInteractionAction> = emptyMap(),
        val reason: String? = null
    )

    private fun declarations(document: RichDocument): Declarations {
        val fields = linkedMapOf<String, RichInteractionField>()
        val actions = linkedMapOf<String, RichInteractionAction>()
        val keys = mutableSetOf<String>()
        var visited = 0
        var declarationChars = 0L
        var error: String? = null
        fun visit(nodes: List<RichNode>, depth: Int): Boolean {
            if (error != null) return false
            if (depth > limits.maxDepth) {
                error = "depth-limit"
                return false
            }
            var complete = true
            for (node in nodes) {
                if (++visited > limits.maxNodes) {
                    error = "node-limit"
                    return false
                }
                if (node is RichNode.Incomplete || node is RichNode.Invalid) complete = false
                if (node !is RichNode.Component) continue
                if (node.range.start < 0 ||
                    node.range.endExclusive < node.range.start ||
                    node.range.endExclusive > document.source.length ||
                    document.source.substring(node.range.start, node.range.endExclusive) != node.raw
                ) {
                    error = "invalid-component-range"
                    return false
                }
                val childrenComplete = visit(node.children, depth + 1)
                if (error != null) return false
                if (!childrenComplete) {
                    complete = false
                    continue
                }
                val binding = try {
                    registry.bind(node)
                } catch (_: Exception) {
                    error = "adapter-failed"
                    return false
                }
                if (binding != null) {
                    if (binding.fields.size > limits.maxFields - fields.size || binding.actions.size > limits.maxActions - actions.size) {
                        error = "binding-limit"
                        return false
                    }
                    for (field in binding.fields) {
                        if (!keyPattern.matches(field.key) || !keys.add(field.key) || !validType(field.type) || !accepts(field.type, field.initialValue)) {
                            error = "invalid-or-duplicate-field"
                            return false
                        }
                        declarationChars += field.key.length + valueChars(field.initialValue) + when (val type = field.type) {
                            is RichInteractionFieldType.Choice -> type.options.sumOf { it.length.toLong() }
                            is RichInteractionFieldType.MultiChoice -> type.options.sumOf { it.length.toLong() }
                            else -> 0L
                        }
                        if (declarationChars > limits.maxDeclarationChars) {
                            error = "declaration-limit"
                            return false
                        }
                        fields[field.key] = freezeField(field)
                    }
                    for (action in binding.actions) {
                        if (!keyPattern.matches(action.key) ||
                            !keys.add(action.key) ||
                            action.prompt.isBlank() ||
                            action.prompt.length > limits.maxSnapshotChars ||
                            (action.sourceLabel?.length ?: 0) > limits.maxValueChars ||
                            action.refs.size > limits.maxFields ||
                            action.refs.distinct().size != action.refs.size ||
                            action.refs.any { !keyPattern.matches(it) }
                        ) {
                            error = "invalid-or-duplicate-action"
                            return false
                        }
                        declarationChars += action.key.length.toLong() + action.prompt.length + (action.sourceLabel?.length ?: 0) +
                            action.refs.sumOf { it.length.toLong() }
                        if (declarationChars > limits.maxDeclarationChars) {
                            error = "declaration-limit"
                            return false
                        }
                        actions[action.key] = action.copy(refs = Collections.unmodifiableList(action.refs.toList()))
                    }
                }
            }
            return complete
        }
        visit(document.nodes, 0)
        if (error != null) return Declarations(reason = error)
        if (fields.values.sumOf { valueChars(it.initialValue) } > limits.maxSessionValueChars) return Declarations(reason = "state-limit")
        // An unresolved or private ref is never silently omitted from an otherwise executable action.
        val allowedActions = actions.filterValues { action -> action.refs.all { fields[it]?.exportable == true } }
        return Declarations(fields, allowedActions)
    }

    private fun validType(type: RichInteractionFieldType): Boolean = when (type) {
        is RichInteractionFieldType.Text -> type.maxLength in 0..limits.maxValueChars
        RichInteractionFieldType.BooleanType -> true
        is RichInteractionFieldType.NumberType -> (type.minimum == null || type.minimum.isFinite()) &&
            (type.maximum == null || type.maximum.isFinite()) &&
            (type.minimum == null || type.maximum == null || type.minimum <= type.maximum)
        is RichInteractionFieldType.Choice -> validOptions(type.options)
        is RichInteractionFieldType.MultiChoice -> validOptions(type.options) && type.maxSelections in 0..type.options.size
    }

    private fun validOptions(options: Set<String>): Boolean = options.size in 1..limits.maxOptions &&
        options.all { it.length <= limits.maxValueChars }

    private fun accepts(type: RichInteractionFieldType, value: RichInteractionValue): Boolean = when (type) {
        is RichInteractionFieldType.Text -> value is RichInteractionValue.Text && value.value.length <= type.maxLength
        RichInteractionFieldType.BooleanType -> value is RichInteractionValue.BooleanValue
        is RichInteractionFieldType.NumberType ->
            value is RichInteractionValue.NumberValue &&
                value.value.isFinite() &&
                (type.minimum == null || value.value >= type.minimum) &&
                (type.maximum == null || value.value <= type.maximum)
        is RichInteractionFieldType.Choice -> value is RichInteractionValue.Choice && value.value in type.options
        is RichInteractionFieldType.MultiChoice ->
            value is RichInteractionValue.MultiChoice &&
                value.values.size <= type.maxSelections &&
                type.options.containsAll(value.values)
    }

    private fun freezeField(field: RichInteractionField): RichInteractionField = field.copy(
        type = when (val type = field.type) {
            is RichInteractionFieldType.Choice -> type.copy(options = immutableSet(type.options))
            is RichInteractionFieldType.MultiChoice -> type.copy(options = immutableSet(type.options))
            else -> type
        },
        initialValue = freezeValue(field.initialValue)
    )

    private fun freezeValue(value: RichInteractionValue): RichInteractionValue = when (value) {
        is RichInteractionValue.MultiChoice -> value.copy(values = immutableSet(value.values))
        else -> value
    }

    private fun valueText(value: RichInteractionValue): String = when (value) {
        is RichInteractionValue.Text -> json(value.value)
        is RichInteractionValue.BooleanValue -> value.value.toString()
        is RichInteractionValue.NumberValue -> value.value.toString()
        is RichInteractionValue.Choice -> json(value.value)
        is RichInteractionValue.MultiChoice -> value.values.sorted().joinToString(",", "[", "]", transform = ::json)
    }

    private fun valueChars(value: RichInteractionValue): Long = when (value) {
        is RichInteractionValue.Text -> value.value.length.toLong()
        is RichInteractionValue.BooleanValue -> 5L
        is RichInteractionValue.NumberValue -> 24L
        is RichInteractionValue.Choice -> value.value.length.toLong()
        is RichInteractionValue.MultiChoice -> value.values.sumOf { it.length.toLong() }
    }

    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                in '\u0000'..'\u001f' -> append("\\u${character.code.toString(16).padStart(4, '0')}")
                else -> append(character)
            }
        }
        append('"')
    }

    private fun fingerprint(source: String): String = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun snapshotFingerprint(snapshot: RichInteractionSnapshot): String = fingerprint(
        buildString {
            fun part(value: String) {
                append(value.length).append(':').append(value)
            }
            part(snapshot.identity.chatId.toString())
            part(snapshot.identity.turnKey)
            part(snapshot.identity.platformUid)
            part(snapshot.identity.revisionKey)
            part(snapshot.token.incarnation.toString())
            part(snapshot.token.generation.toString())
            part(snapshot.documentGeneration.toString())
            part(snapshot.sourceFingerprint)
            part(snapshot.actionKey)
            part(snapshot.sourceLabel ?: "")
            part((snapshot.sourceLabel == null).toString())
            part(snapshot.prompt)
            part(snapshot.text)
            part(snapshot.eventId)
            snapshot.values.toSortedMap().forEach { (key, value) ->
                part(key)
                part(value.javaClass.name)
                part(valueText(value))
            }
        }
    )

    private fun rejected(reason: String) = RichInteractionResult.Rejected(reason)

    private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(values))

    private fun <T> immutableSet(values: Set<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
}
