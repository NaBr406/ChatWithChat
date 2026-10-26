package cn.nabr.chatwithchat.data.richcontent.interaction

import cn.nabr.chatwithchat.data.richcontent.RichDocument
import cn.nabr.chatwithchat.data.richcontent.RichNode
import cn.nabr.chatwithchat.data.richcontent.SourceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RichInteractionRuntimeTest {
    private val identity = RichInteractionIdentity(7, "turn-1", "provider-1", "revision-1")
    private val registry = RichInteractionRegistry(
        listOf(
            adapter { node ->
                val key = node.attributes.getValue("key")
                when (node.attributes["kind"]) {
                    "text" -> RichInteractionBinding(fields = listOf(textField(key)))
                    "private" -> RichInteractionBinding(fields = listOf(textField(key).copy(exportable = false)))
                    "number" -> RichInteractionBinding(
                        fields = listOf(
                            RichInteractionField(
                                key,
                                RichInteractionFieldType.NumberType(0.0, 10.0),
                                RichInteractionValue.NumberValue(2.0),
                                true
                            )
                        )
                    )
                    "multi" -> RichInteractionBinding(
                        fields = listOf(
                            RichInteractionField(
                                key,
                                RichInteractionFieldType.MultiChoice(setOf("a", "b"), 1),
                                RichInteractionValue.MultiChoice(emptySet()),
                                true
                            )
                        )
                    )
                    "group" -> RichInteractionBinding(fields = listOf(textField("first"), textField("second")))
                    "action" -> RichInteractionBinding(actions = listOf(RichInteractionAction(key, "Review values", listOf("answer"), "Review")))
                    "private-action" -> RichInteractionBinding(actions = listOf(RichInteractionAction(key, "Review", listOf("secret"))))
                    "missing-action" -> RichInteractionBinding(actions = listOf(RichInteractionAction(key, "Review", listOf("missing"))))
                    else -> null
                }
            }
        )
    )

    @Test
    fun defaultRegistry_hasNoInteractiveComponents_andKeepsTokenForIdenticalSync() {
        val runtime = RichInteractionRuntime()
        val document = document("answer" to "text")
        val first = runtime.sync(identity, document, true)
        val second = runtime.sync(identity, document, true)
        assertTrue(first.fields.isEmpty())
        assertTrue(first.actions.isEmpty())
        assertFalse(first.isInteractionEnabled)
        assertEquals(first.token, second.token)
        assertRejected("interaction-disabled", runtime.dispatch(identity, RichInteractionEvent.SetValue(first.token, "e", "answer", RichInteractionValue.Text("x"))))
    }

    @Test
    fun registry_rejectsDuplicateAndMalformedNames() {
        expectIllegalArgument { RichInteractionRegistry(listOf(adapter { null }, adapter { null })) }
        expectIllegalArgument {
            RichInteractionRegistry(
                listOf(object : RichInteractionBindingAdapter {
                    override val componentName = "<script>"
                    override fun bind(node: RichNode.Component): RichInteractionBinding? = null
                })
            )
        }
    }

    @Test
    fun identity_isolatesChatTurnProviderAndRevision_andRetainsOffscreenState() {
        val runtime = RichInteractionRuntime(registry)
        val initial = runtime.sync(identity, document("answer" to "text"), true)
        set(runtime, initial, "answer", RichInteractionValue.Text("retained"))
        val otherIdentities = listOf(
            identity.copy(chatId = 8),
            identity.copy(turnKey = "turn-2"),
            identity.copy(platformUid = "provider-2"),
            identity.copy(revisionKey = "revision-2")
        )
        otherIdentities.forEach {
            assertEquals(RichInteractionValue.Text("initial"), runtime.sync(it, document("answer" to "text"), true).values["answer"])
        }
        assertEquals(RichInteractionValue.Text("retained"), runtime.observe(identity)!!.value.values["answer"])
        assertEquals(RichInteractionValue.Text("retained"), runtime.sync(identity, document("answer" to "text"), true).values["answer"])
    }

    @Test
    fun append_retainsValues_butNewDeclarationsRollbackAndReplacementInvalidateEvents() {
        val runtime = RichInteractionRuntime(registry)
        val base = document("answer" to "text")
        val first = runtime.sync(identity, base, true)
        set(runtime, first, "answer", RichInteractionValue.Text("edited"))
        val appendedDocument = document("answer" to "text", "later" to "text")
        val appended = runtime.sync(identity, appendedDocument, true)
        assertNotEquals(first.token, appended.token)
        assertEquals(RichInteractionValue.Text("edited"), appended.values["answer"])
        val rollback = runtime.sync(identity, base, true)
        assertNotEquals(appended.token, rollback.token)
        assertEquals(RichInteractionValue.Text("initial"), rollback.values["answer"])
        assertFalse(rollback.values.containsKey("later"))
        val withSuffix = base.copy(source = base.source + "A")
        val suffixState = runtime.sync(identity, withSuffix, true)
        assertEquals(rollback.token, suffixState.token)
        set(runtime, suffixState, "answer", RichInteractionValue.Text("again"), "suffix")
        val replaced = runtime.sync(identity, withSuffix.copy(source = base.source + "B"), true)
        assertNotEquals(suffixState.token, replaced.token)
        assertEquals(RichInteractionValue.Text("initial"), replaced.values["answer"])
    }

    @Test
    fun generationReset_andExplicitRetry_discardOldValuesAndOldEvents() {
        val runtime = RichInteractionRuntime(registry)
        val base = document("answer" to "text")
        val first = runtime.sync(identity, base, true)
        set(runtime, first, "answer", RichInteractionValue.Text("old"))
        val regenerated = runtime.sync(identity, base.copy(generation = 9), true)
        assertNotEquals(first.token, regenerated.token)
        assertEquals(RichInteractionValue.Text("initial"), regenerated.values["answer"])
        assertRejected("stale-token", runtime.dispatch(identity, RichInteractionEvent.SetValue(first.token, "queued", "answer", RichInteractionValue.Text("old"))))
        val flow = runtime.observe(identity)!!
        runtime.reset(identity)
        assertTrue(flow.value.values.isEmpty())
        val retried = runtime.sync(identity, base.copy(generation = 9), true)
        assertNotEquals(regenerated.token, retried.token)
        assertRejected("stale-token", runtime.dispatch(identity, RichInteractionEvent.SetValue(regenerated.token, "queued", "answer", RichInteractionValue.Text("old"))))
    }

    @Test
    fun disabledOrStreamingActions_neverReviveQueuedEventsWhenEnabled() {
        val runtime = RichInteractionRuntime(registry)
        val base = document("answer" to "text", "review" to "action")
        val streaming = runtime.sync(identity, base.copy(isStreaming = true), true)
        assertFalse(streaming.isInteractionEnabled)
        assertRejected("interaction-disabled", runtime.dispatch(identity, RichInteractionEvent.InvokeAction(streaming.token, "stream", "review")))
        val enabled = runtime.sync(identity, base, true)
        assertNotEquals(streaming.token, enabled.token)
        assertRejected("stale-token", runtime.dispatch(identity, RichInteractionEvent.InvokeAction(streaming.token, "stream", "review")))
        val disabled = runtime.sync(identity, base, false)
        val enabledAgain = runtime.sync(identity, base, true)
        assertNotEquals(enabled.token, enabledAgain.token)
        assertRejected("stale-token", runtime.dispatch(identity, RichInteractionEvent.InvokeAction(disabled.token, "disabled", "review")))
    }

    @Test
    fun incompleteAndInvalidRaw_areNeverPassedToBindingAdapters() {
        var calls = 0
        val runtime = RichInteractionRuntime(
            RichInteractionRegistry(
                listOf(
                    adapter {
                        calls++
                        RichInteractionBinding(fields = listOf(textField("leaked")))
                    }
                )
            )
        )
        val raw = "<fixture key=\"leaked\"/>"
        listOf<RichNode>(
            RichNode.Incomplete(raw, "fixture", SourceRange(0, raw.length)),
            RichNode.Invalid(raw, "invalid", SourceRange(0, raw.length))
        ).forEach { node ->
            val state = runtime.sync(identity, RichDocument(raw, listOf(node), emptyList()), true)
            assertTrue(state.fields.isEmpty())
        }
        val invalidChild = RichNode.Invalid(raw, "invalid", SourceRange(0, raw.length))
        val parent = RichNode.Component("fixture", mapOf("key" to "parent"), listOf(invalidChild), raw, SourceRange(0, raw.length))
        assertTrue(runtime.sync(identity, RichDocument(raw, listOf(parent), emptyList()), true).fields.isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun duplicateKeys_failClosed_evenAcrossMultipleFieldsOrActionAndField() {
        val runtime = RichInteractionRuntime(registry)
        val duplicateFields = runtime.sync(identity, document("answer" to "text", "answer" to "text"), true)
        assertFalse(duplicateFields.isInteractionEnabled)
        assertTrue(duplicateFields.values.isEmpty())
        val collision = runtime.sync(identity, document("answer" to "text", "answer" to "action"), true)
        assertFalse(collision.isInteractionEnabled)
        assertTrue(collision.actions.isEmpty())
        val multiple = runtime.sync(identity, document("group" to "group"), true)
        assertEquals(setOf("first", "second"), multiple.fields.keys)
    }

    @Test
    fun setValue_checksDeclaredTypesNumericRangeAndSelectionWhitelist() {
        val runtime = RichInteractionRuntime(registry)
        val state = runtime.sync(identity, document("answer" to "text", "count" to "number", "choices" to "multi"), true)
        assertRejected("invalid-value", runtime.dispatch(identity, RichInteractionEvent.SetValue(state.token, "wrong", "answer", RichInteractionValue.BooleanValue(true))))
        assertRejected("invalid-value", runtime.dispatch(identity, RichInteractionEvent.SetValue(state.token, "large", "count", RichInteractionValue.NumberValue(11.0))))
        assertRejected("invalid-value", runtime.dispatch(identity, RichInteractionEvent.SetValue(state.token, "unknown", "choices", RichInteractionValue.MultiChoice(setOf("outside")))))
        assertRejected("invalid-value", runtime.dispatch(identity, RichInteractionEvent.SetValue(state.token, "many", "choices", RichInteractionValue.MultiChoice(setOf("a", "b")))))
        assertTrue(runtime.dispatch(identity, RichInteractionEvent.SetValue(state.token, "valid", "count", RichInteractionValue.NumberValue(10.0))) is RichInteractionResult.Updated)
        expectIllegalArgument { RichInteractionValue.NumberValue(Double.NaN) }
        expectIllegalArgument { RichInteractionValue.NumberValue(Double.POSITIVE_INFINITY) }
    }

    @Test
    fun booleanAndSingleChoice_acceptOnlyOwnTypesAndDeclaredOptions() {
        val runtime = RichInteractionRuntime(
            RichInteractionRegistry(
                listOf(
                    adapter {
                        RichInteractionBinding(
                            fields = listOf(
                                RichInteractionField("enabled", RichInteractionFieldType.BooleanType, RichInteractionValue.BooleanValue(false)),
                                RichInteractionField("option", RichInteractionFieldType.Choice(setOf("a", "b")), RichInteractionValue.Choice("a"))
                            )
                        )
                    }
                )
            )
        )
        val state = runtime.sync(identity, document("group" to "group"), true)
        set(runtime, state, "enabled", RichInteractionValue.BooleanValue(true), "boolean")
        set(runtime, state, "option", RichInteractionValue.Choice("b"), "choice")
        assertRejected("invalid-value", runtime.dispatch(identity, RichInteractionEvent.SetValue(state.token, "text", "option", RichInteractionValue.Text("a"))))
        assertRejected("invalid-value", runtime.dispatch(identity, RichInteractionEvent.SetValue(state.token, "unknown", "option", RichInteractionValue.Choice("outside"))))
        assertEquals(RichInteractionValue.BooleanValue(true), runtime.observe(identity)!!.value.values["enabled"])
    }

    @Test
    fun actionSnapshot_containsOnlyExplicitExportableRefs_andIsDetachedFromLaterEdits() {
        val runtime = RichInteractionRuntime(registry)
        val state = runtime.sync(identity, document("answer" to "text", "secret" to "private", "unreferenced" to "text", "review" to "action"), true)
        set(runtime, state, "answer", RichInteractionValue.Text("a\"\n} <script>"))
        val result = runtime.dispatch(identity, RichInteractionEvent.InvokeAction(state.token, "review-event", "review")) as RichInteractionResult.Action
        val snapshot = result.snapshot
        assertEquals(identity, snapshot.identity)
        assertEquals("Review", snapshot.sourceLabel)
        assertEquals("Review values", snapshot.prompt)
        assertEquals(setOf("answer"), snapshot.values.keys)
        assertTrue(snapshot.text.contains("a\\\"\\u000a} <script>"))
        assertFalse(snapshot.text.contains("secret"))
        assertFalse(snapshot.text.contains("unreferenced"))
        assertFalse(snapshot.text.contains("<fixture"))
        assertTrue(runtime.isCurrent(snapshot))
        assertFalse(runtime.isCurrent(snapshot.copy(text = "forged")))
        assertFalse(runtime.isCurrent(snapshot.copy(values = mapOf("answer" to RichInteractionValue.Text("forged")))))
        assertFalse(runtime.isCurrent(snapshot.copy(eventId = "not-issued")))
        set(runtime, state, "answer", RichInteractionValue.Text("later"), "later-edit")
        assertEquals(RichInteractionValue.Text("a\"\n} <script>"), snapshot.values["answer"])
        assertEquals(snapshot.text, result.snapshot.text)
        runtime.sync(identity, document("answer" to "text", "secret" to "private", "unreferenced" to "text", "review" to "action", "extra" to "text"), true)
        assertFalse(runtime.isCurrent(snapshot))
    }

    @Test
    fun privateOrUnresolvedRefs_disableTheEntireAction_withoutCollectingExtraFields() {
        val runtime = RichInteractionRuntime(registry)
        val state = runtime.sync(identity, document("secret" to "private", "one" to "private-action", "two" to "missing-action"), true)
        assertTrue(state.actions.isEmpty())
        assertRejected("unknown-action", runtime.dispatch(identity, RichInteractionEvent.InvokeAction(state.token, "private", "one")))
        assertRejected("unknown-action", runtime.dispatch(identity, RichInteractionEvent.InvokeAction(state.token, "missing", "two")))
    }

    @Test
    fun duplicateDispatch_isRejected_andBoundedLedgerNeverForgetsIdsWithinGeneration() {
        val runtime = RichInteractionRuntime(registry, RichInteractionLimits(maxEventsPerGeneration = 1))
        val base = document("answer" to "text", "review" to "action")
        val state = runtime.sync(identity, base, true)
        val event = RichInteractionEvent.InvokeAction(state.token, "click", "review")
        assertTrue(runtime.dispatch(identity, event) is RichInteractionResult.Action)
        assertRejected("duplicate-event", runtime.dispatch(identity, event))
        assertRejected("event-limit", runtime.dispatch(identity, event.copy(eventId = "another")))
        val newGeneration = runtime.sync(identity, base.copy(generation = 1), true)
        assertTrue(runtime.dispatch(identity, event.copy(token = newGeneration.token)) is RichInteractionResult.Action)
    }

    @Test
    fun lruEviction_clearsObservableState_andRecreatedIdentityRejectsOldIncarnation() {
        val runtime = RichInteractionRuntime(registry, RichInteractionLimits(maxSessions = 2))
        val base = document("answer" to "text")
        val first = runtime.sync(identity, base, true)
        val secondIdentity = identity.copy(turnKey = "second")
        val second = runtime.sync(secondIdentity, base, true)
        val secondFlow = runtime.observe(secondIdentity)!!
        runtime.observe(identity)
        runtime.sync(identity.copy(turnKey = "third"), base, true)
        assertNull(runtime.observe(secondIdentity))
        assertTrue(secondFlow.value.values.isEmpty())
        assertFalse(secondFlow.value.isInteractionEnabled)
        val recreated = runtime.sync(secondIdentity, base, true)
        assertNotEquals(second.token.incarnation, recreated.token.incarnation)
        assertRejected("stale-token", runtime.dispatch(secondIdentity, RichInteractionEvent.SetValue(second.token, "old", "answer", RichInteractionValue.Text("old"))))
        assertNotEquals(first.token.incarnation, recreated.token.incarnation)
    }

    @Test
    fun adapterAndEventCollections_areDefensivelyCopied() {
        val options = mutableSetOf("a", "b")
        val initial = mutableSetOf("a")
        val refs = mutableListOf("selected")
        val runtime = RichInteractionRuntime(
            RichInteractionRegistry(
                listOf(
                    adapter {
                        RichInteractionBinding(
                            fields = listOf(RichInteractionField("selected", RichInteractionFieldType.MultiChoice(options), RichInteractionValue.MultiChoice(initial), true)),
                            actions = listOf(RichInteractionAction("review", "Review", refs))
                        )
                    }
                )
            )
        )
        val state = runtime.sync(identity, document("anything" to "group"), true)
        options += "unexpected"
        initial += "unexpected"
        refs += "private"
        assertEquals(setOf("a"), (state.values.getValue("selected") as RichInteractionValue.MultiChoice).values)
        assertEquals(listOf("selected"), state.actions.getValue("review").refs)
        val edited = mutableSetOf("b")
        set(runtime, state, "selected", RichInteractionValue.MultiChoice(edited))
        edited.clear()
        val snapshot = (runtime.dispatch(identity, RichInteractionEvent.InvokeAction(state.token, "review", "review")) as RichInteractionResult.Action).snapshot
        assertEquals(setOf("b"), (snapshot.values.getValue("selected") as RichInteractionValue.MultiChoice).values)
        expectUnsupportedOperation { (snapshot.values as MutableMap<String, RichInteractionValue>).clear() }
        expectUnsupportedOperation { ((snapshot.values.getValue("selected") as RichInteractionValue.MultiChoice).values as MutableSet<String>).clear() }
    }

    @Test
    fun resourceLimitsAndBrokenAdapters_failClosed_withoutRetainingOldFields() {
        val runtime = RichInteractionRuntime(registry, RichInteractionLimits(maxFields = 1, maxSourceChars = 100))
        runtime.sync(identity, document("answer" to "text"), true)
        val limited = runtime.sync(identity, document("answer" to "text", "second" to "text"), true)
        assertTrue(limited.values.isEmpty())
        assertFalse(limited.isInteractionEnabled)
        val oversized = runtime.sync(identity, RichDocument("x".repeat(101), emptyList(), emptyList()), true)
        assertEquals("source-limit", oversized.rejectionReason)
        assertEquals("", oversized.sourceFingerprint)
        val broken = RichInteractionRuntime(RichInteractionRegistry(listOf(adapter { error("bad adapter") })))
        assertEquals("adapter-failed", broken.sync(identity, document("answer" to "text"), true).rejectionReason)
    }

    @Test
    fun invalidDeclarationsAndSnapshotSize_areRejected() {
        val invalid = RichInteractionRuntime(
            RichInteractionRegistry(
                listOf(
                    adapter {
                        RichInteractionBinding(fields = listOf(RichInteractionField("answer", RichInteractionFieldType.NumberType(Double.NaN), RichInteractionValue.NumberValue(1.0))))
                    }
                )
            )
        )
        assertFalse(invalid.sync(identity, document("answer" to "text"), true).isInteractionEnabled)
        val limited = RichInteractionRuntime(registry, RichInteractionLimits(maxSnapshotChars = 20))
        val state = limited.sync(identity, document("answer" to "text", "review" to "action"), true)
        assertRejected("snapshot-limit", limited.dispatch(identity, RichInteractionEvent.InvokeAction(state.token, "review", "review")))
    }

    @Test
    fun totalStateAndDeclarationBudgets_boundAggregateMemory() {
        val runtime = RichInteractionRuntime(registry, RichInteractionLimits(maxSessionValueChars = 8))
        val state = runtime.sync(identity, document("answer" to "text"), true)
        assertTrue(state.isInteractionEnabled)
        assertRejected("state-limit", runtime.dispatch(identity, RichInteractionEvent.SetValue(state.token, "large", "answer", RichInteractionValue.Text("123456789"))))
        assertEquals(RichInteractionValue.Text("initial"), runtime.observe(identity)!!.value.values["answer"])
        val aggregate = runtime.sync(identity, document("answer" to "text", "other" to "text"), true)
        assertFalse(aggregate.isInteractionEnabled)
        assertEquals("state-limit", aggregate.rejectionReason)
        val declarationLimited = RichInteractionRuntime(registry, RichInteractionLimits(maxDeclarationChars = 5))
        assertEquals("declaration-limit", declarationLimited.sync(identity, document("answer" to "text"), true).rejectionReason)
    }

    @Test
    fun clear_invalidatesExistingFlowAndSnapshot() {
        val runtime = RichInteractionRuntime(registry)
        val state = runtime.sync(identity, document("answer" to "text", "review" to "action"), true)
        val flow = runtime.observe(identity)!!
        val snapshot = (runtime.dispatch(identity, RichInteractionEvent.InvokeAction(state.token, "review", "review")) as RichInteractionResult.Action).snapshot
        runtime.clear()
        assertTrue(flow.value.values.isEmpty())
        assertFalse(runtime.isCurrent(snapshot))
        assertRejected("missing-session", runtime.dispatch(identity, RichInteractionEvent.InvokeAction(state.token, "queued", "review")))
    }

    private fun textField(key: String) = RichInteractionField(key, RichInteractionFieldType.Text(), RichInteractionValue.Text("initial"), true)

    private fun adapter(block: (RichNode.Component) -> RichInteractionBinding?) = object : RichInteractionBindingAdapter {
        override val componentName = "fixture"
        override fun bind(node: RichNode.Component): RichInteractionBinding? = block(node)
    }

    private fun document(vararg entries: Pair<String, String>): RichDocument {
        val source = StringBuilder()
        val nodes = entries.map { (key, kind) ->
            if (source.isNotEmpty()) source.append('\n')
            val start = source.length
            val raw = "<fixture key=\"$key\" kind=\"$kind\"/>"
            source.append(raw)
            RichNode.Component("fixture", mapOf("key" to key, "kind" to kind), emptyList(), raw, SourceRange(start, source.length))
        }
        return RichDocument(source.toString(), nodes, emptyList())
    }

    private fun set(runtime: RichInteractionRuntime, state: RichInteractionSessionState, key: String, value: RichInteractionValue, eventId: String = "edit") {
        assertTrue(runtime.dispatch(state.identity, RichInteractionEvent.SetValue(state.token, eventId, key, value)) is RichInteractionResult.Updated)
    }

    private fun assertRejected(reason: String, result: RichInteractionResult) {
        assertEquals(RichInteractionResult.Rejected(reason), result)
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }

    private fun expectUnsupportedOperation(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) {
            // Exposed collections must be immutable.
        }
    }
}
