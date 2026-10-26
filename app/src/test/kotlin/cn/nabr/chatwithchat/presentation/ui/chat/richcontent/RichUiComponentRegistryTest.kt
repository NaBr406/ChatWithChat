package cn.nabr.chatwithchat.presentation.ui.chat.richcontent

import androidx.compose.runtime.Composable
import cn.nabr.chatwithchat.data.richcontent.AttributeKind
import cn.nabr.chatwithchat.data.richcontent.AttributeSpec
import cn.nabr.chatwithchat.data.richcontent.ComponentSpec
import cn.nabr.chatwithchat.data.richcontent.RichComponentCatalog
import cn.nabr.chatwithchat.data.richcontent.RichContentParser
import cn.nabr.chatwithchat.data.richcontent.RichNode
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionAction
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionBinding
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionEvent
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionField
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionFieldType
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionIdentity
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionResult
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionRuntime
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RichUiComponentRegistryTest {
    private val identity = RichInteractionIdentity(1, "turn", "provider", "revision")

    @Test
    fun emptyRegistry_preservesProtocolWithoutRegisteringRenderersOrInteractions() {
        val registry = RichUiComponentRegistry()
        assertEquals(RichComponentCatalog.components, registry.catalog)
        RichComponentCatalog.components.keys.forEach { assertNull(registry.definition(it)) }
        val document = RichContentParser(registry.catalog).parse("<ui-collapse key=\"detail\" title=\"Details\">Text</ui-collapse>")
        val state = RichInteractionRuntime(registry.interactionRegistry).sync(identity, document, true)
        assertFalse(state.isInteractionEnabled)
        assertTrue(state.fields.isEmpty())
        assertTrue(state.actions.isEmpty())
    }

    @Test
    fun registry_rejectsDuplicateNamesAndMalformedNames() {
        val definition = Definition(extensionSpec())
        expectIllegalArgument { RichUiComponentRegistry(listOf(definition, definition)) }
        listOf("fixture", "UI-fixture", "ui-", "ui-<script>").forEach { name ->
            expectIllegalArgument { RichUiComponentRegistry(listOf(Definition(extensionSpec().copy(name = name)))) }
        }
    }

    @Test
    fun staticRenderers_cannotBeReplacedEvenWithIdenticalSpecification() {
        listOf("ui-card", "ui-row", "ui-col", "ui-metric", "ui-callout", "ui-steps", "ui-step").forEach { name ->
            expectIllegalArgument { RichUiComponentRegistry(listOf(Definition(RichComponentCatalog.components.getValue(name)))) }
        }
    }

    @Test
    fun legacyProtocolRenderer_requiresUnchangedSpecification() {
        val existing = RichComponentCatalog.components.getValue("ui-slider")
        val definition = Definition(existing)
        val registry = RichUiComponentRegistry(listOf(definition))
        assertSame(definition, registry.definition(existing.name))
        assertEquals(existing, registry.catalog.getValue(existing.name))
        val valid = RichContentParser(registry.catalog).parse("<ui-slider key=\"count\" label=\"Count\" min=\"0\" max=\"10\" step=\"1\" default=\"2\"/>")
        assertTrue(valid.nodes.single() is RichNode.Component)
        expectIllegalArgument { RichUiComponentRegistry(listOf(Definition(existing.copy(attributes = emptyMap())))) }
        expectIllegalArgument { RichUiComponentRegistry(listOf(Definition(existing.copy(selfClosing = false)))) }
    }

    @Test
    fun registry_snapshotsNestedSpecificationCollections() {
        val enumValues = mutableSetOf("one", "two")
        val attributes = mutableMapOf(
            "key" to AttributeSpec(required = true),
            "mode" to AttributeSpec(AttributeKind.ENUM, true, enumValues)
        )
        val children = mutableSetOf("ui-card")
        val definitions = mutableListOf<RichUiComponentDefinition>(Definition(extensionSpec().copy(attributes = attributes, allowedChildren = children)))
        val registry = RichUiComponentRegistry(definitions)
        definitions.clear()
        attributes.clear()
        enumValues += "unexpected"
        children += "ui-row"
        val snapshot = registry.catalog.getValue("ui-fixture")
        assertEquals(setOf("key", "mode"), snapshot.attributes.keys)
        assertEquals(setOf("one", "two"), snapshot.attributes.getValue("mode").values)
        assertEquals(setOf("ui-card"), snapshot.allowedChildren)
        assertTrue(registry.definition("ui-fixture") != null)
        expectUnsupportedOperation { (registry.catalog as MutableMap<String, ComponentSpec>).clear() }
        expectUnsupportedOperation { (snapshot.attributes as MutableMap<String, AttributeSpec>).clear() }
        expectUnsupportedOperation { (snapshot.attributes.getValue("mode").values as MutableSet<String>).clear() }
        expectUnsupportedOperation { (snapshot.allowedChildren as MutableSet<String>).clear() }
    }

    @Test
    fun extensionCatalog_drivesParserAndRuntimeBindingWithoutProductionComponentNames() {
        val definition = Definition(extensionSpec()) { node ->
            val key = node.attributes.getValue("key")
            RichInteractionBinding(
                fields = listOf(
                    RichInteractionField(
                        key,
                        RichInteractionFieldType.Text(),
                        RichInteractionValue.Text("initial"),
                        true
                    )
                )
            )
        }
        val source = "<ui-fixture key=\"answer\" mode=\"one\"/>"
        assertTrue(RichContentParser().parse(source).nodes.single() is RichNode.Invalid)
        val registry = RichUiComponentRegistry(listOf(definition))
        val document = RichContentParser(registry.catalog).parse(source)
        assertTrue(document.nodes.single() is RichNode.Component)
        val runtime = RichInteractionRuntime(registry.interactionRegistry)
        val state = runtime.sync(identity, document, true)
        assertEquals(setOf("answer"), state.fields.keys)
        assertTrue(state.isInteractionEnabled)
        val updated = runtime.dispatch(identity, RichInteractionEvent.SetValue(state.token, "edit", "answer", RichInteractionValue.Text("local")))
        assertTrue(updated is RichInteractionResult.Updated)
        assertEquals(RichInteractionValue.Text("local"), runtime.observe(identity)!!.value.values["answer"])
        val invalid = RichContentParser(registry.catalog).parse(source.replace("one", "outside"))
        assertTrue(invalid.nodes.single() is RichNode.Invalid)
        assertTrue(runtime.sync(identity, invalid, true).fields.isEmpty())
    }

    @Test
    fun duplicateExtensionBindingKeys_failClosedEvenWhenParserAcceptsBothNodes() {
        val registry = RichUiComponentRegistry(
            listOf(
                Definition(extensionSpec()) { node ->
                    RichInteractionBinding(
                        fields = listOf(
                            RichInteractionField(
                                node.attributes.getValue("key"),
                                RichInteractionFieldType.Text(),
                                RichInteractionValue.Text("initial")
                            )
                        )
                    )
                }
            )
        )
        val document = RichContentParser(registry.catalog).parse("<ui-fixture key=\"same\" mode=\"one\"/>\n<ui-fixture key=\"same\" mode=\"two\"/>")
        assertEquals(2, document.nodes.filterIsInstance<RichNode.Component>().size)
        val state = RichInteractionRuntime(registry.interactionRegistry).sync(identity, document, true)
        assertFalse(state.isInteractionEnabled)
        assertTrue(state.fields.isEmpty())
        assertTrue(state.actions.isEmpty())
    }

    @Test
    fun changedActionDeclaration_invalidatesQueuedEventsWhileKeepingUnchangedFields() {
        var prompt = "First prompt"
        val registry = RichUiComponentRegistry(
            listOf(
                Definition(extensionSpec()) {
                    RichInteractionBinding(
                        fields = listOf(RichInteractionField("answer", RichInteractionFieldType.Text(), RichInteractionValue.Text("initial"), true)),
                        actions = listOf(RichInteractionAction("review", prompt, listOf("answer")))
                    )
                }
            )
        )
        val document = RichContentParser(registry.catalog).parse("<ui-fixture key=\"fixture\" mode=\"one\"/>")
        val runtime = RichInteractionRuntime(registry.interactionRegistry)
        val first = runtime.sync(identity, document, true)
        runtime.dispatch(identity, RichInteractionEvent.SetValue(first.token, "edit", "answer", RichInteractionValue.Text("kept")))
        prompt = "Second prompt"
        val changed = runtime.sync(identity, document.copy(source = document.source + "\nMore"), true)
        assertNotEquals(first.token, changed.token)
        assertEquals(RichInteractionValue.Text("kept"), changed.values["answer"])
        assertEquals(RichInteractionResult.Rejected("stale-token"), runtime.dispatch(identity, RichInteractionEvent.InvokeAction(first.token, "queued", "review")))
        val action = runtime.dispatch(identity, RichInteractionEvent.InvokeAction(changed.token, "fresh", "review")) as RichInteractionResult.Action
        assertEquals("Second prompt", action.snapshot.prompt)
    }

    private fun extensionSpec() = ComponentSpec(
        "ui-fixture",
        mapOf("key" to AttributeSpec(required = true), "mode" to AttributeSpec(AttributeKind.ENUM, true, setOf("one", "two"))),
        markdownChildren = false,
        selfClosing = true
    )

    private class Definition(
        override val spec: ComponentSpec,
        private val binder: (RichNode.Component) -> RichInteractionBinding? = { null }
    ) : RichUiComponentDefinition {
        override fun bind(node: RichNode.Component): RichInteractionBinding? = binder(node)

        @Composable
        override fun Content(scope: RichUiComponentScope) = Unit
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Registration rejects ambiguous or incompatible definitions.
        }
    }

    private fun expectUnsupportedOperation(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) {
            // The parser must see the original registered contract.
        }
    }
}
