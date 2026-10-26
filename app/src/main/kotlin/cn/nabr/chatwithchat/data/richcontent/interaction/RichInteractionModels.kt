package cn.nabr.chatwithchat.data.richcontent.interaction

/** Logical answer identity supplied by the host, independent of a composable's lifetime. */
data class RichInteractionIdentity(
    val chatId: Int,
    val turnKey: String,
    val platformUid: String,
    val revisionKey: String
)

/** Incarnation prevents an evicted/recreated session from accepting an old event. */
data class RichInteractionToken(val incarnation: Long, val generation: Long)

sealed interface RichInteractionValue {
    data class Text(val value: String) : RichInteractionValue
    data class BooleanValue(val value: Boolean) : RichInteractionValue
    data class NumberValue(val value: Double) : RichInteractionValue {
        init {
            require(value.isFinite())
        }
    }
    data class Choice(val value: String) : RichInteractionValue
    data class MultiChoice(val values: Set<String>) : RichInteractionValue
}

sealed interface RichInteractionFieldType {
    data class Text(val maxLength: Int = 4096) : RichInteractionFieldType
    data object BooleanType : RichInteractionFieldType
    data class NumberType(val minimum: Double? = null, val maximum: Double? = null) : RichInteractionFieldType
    data class Choice(val options: Set<String>) : RichInteractionFieldType
    data class MultiChoice(val options: Set<String>, val maxSelections: Int = options.size) : RichInteractionFieldType
}

data class RichInteractionField(
    val key: String,
    val type: RichInteractionFieldType,
    val initialValue: RichInteractionValue,
    val exportable: Boolean = false
)

/** refs is an explicit allowlist of exportable local fields; it never queries arbitrary attributes. */
data class RichInteractionAction(
    val key: String,
    val prompt: String,
    val refs: List<String> = emptyList(),
    val sourceLabel: String? = null
)

data class RichInteractionBinding(
    val fields: List<RichInteractionField> = emptyList(),
    val actions: List<RichInteractionAction> = emptyList()
)

data class RichInteractionSessionState(
    val identity: RichInteractionIdentity,
    val token: RichInteractionToken,
    val fields: Map<String, RichInteractionField> = emptyMap(),
    val actions: Map<String, RichInteractionAction> = emptyMap(),
    val values: Map<String, RichInteractionValue> = emptyMap(),
    val isInteractionEnabled: Boolean = false,
    val documentGeneration: Long? = null,
    val sourceFingerprint: String = "",
    val rejectionReason: String? = null
)

sealed interface RichInteractionEvent {
    val token: RichInteractionToken
    val eventId: String

    data class SetValue(
        override val token: RichInteractionToken,
        override val eventId: String,
        val key: String,
        val value: RichInteractionValue
    ) : RichInteractionEvent

    data class InvokeAction(
        override val token: RichInteractionToken,
        override val eventId: String,
        val key: String
    ) : RichInteractionEvent
}

/** Detached immutable data only. The host decides whether to preview, insert, or send it. */
data class RichInteractionSnapshot(
    val identity: RichInteractionIdentity,
    val token: RichInteractionToken,
    val documentGeneration: Long,
    val sourceFingerprint: String,
    val actionKey: String,
    val sourceLabel: String?,
    val prompt: String,
    val values: Map<String, RichInteractionValue>,
    val text: String,
    val eventId: String
)

sealed interface RichInteractionResult {
    data class Updated(val state: RichInteractionSessionState) : RichInteractionResult
    data class Action(val snapshot: RichInteractionSnapshot) : RichInteractionResult
    data class Rejected(val reason: String) : RichInteractionResult
}

data class RichInteractionLimits(
    val maxSessions: Int = 32,
    val maxFields: Int = 128,
    val maxActions: Int = 64,
    val maxOptions: Int = 128,
    val maxValueChars: Int = 16384,
    val maxSessionValueChars: Int = 65536,
    val maxDeclarationChars: Int = 131072,
    val maxSnapshotChars: Int = 32768,
    val maxEventsPerGeneration: Int = 4096,
    val maxSourceChars: Int = 1048576,
    val maxNodes: Int = 4096,
    val maxDepth: Int = 64
) {
    init {
        require(
            listOf(
                maxSessions, maxFields, maxActions, maxOptions, maxValueChars, maxSessionValueChars,
                maxDeclarationChars, maxSnapshotChars, maxEventsPerGeneration, maxSourceChars, maxNodes, maxDepth
            ).all { it > 0 }
        )
    }
}
