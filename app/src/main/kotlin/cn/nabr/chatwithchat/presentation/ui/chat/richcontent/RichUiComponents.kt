package cn.nabr.chatwithchat.presentation.ui.chat.richcontent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import cn.nabr.chatwithchat.data.richcontent.ComponentSpec
import cn.nabr.chatwithchat.data.richcontent.RichComponentCatalog
import cn.nabr.chatwithchat.data.richcontent.RichNode
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionBindingAdapter
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionEvent
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionRegistry
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionSessionState
import cn.nabr.chatwithchat.data.richcontent.interaction.RichInteractionValue
import java.util.Collections
import java.util.UUID

/** One trusted definition joins the parser contract, state binding and native renderer. */
interface RichUiComponentDefinition : RichInteractionBindingAdapter {
    val spec: ComponentSpec

    override val componentName: String get() = spec.name

    @Composable
    fun Content(scope: RichUiComponentScope)
}

class RichUiComponentRegistry(definitions: List<RichUiComponentDefinition> = emptyList()) {
    private val definitionsByName: Map<String, RichUiComponentDefinition>
    val catalog: Map<String, ComponentSpec>
    val interactionRegistry: RichInteractionRegistry

    init {
        require(definitions.size <= 256)
        require(definitions.all { it.componentName == it.spec.name && it.spec.name.matches(Regex("ui-[a-z][a-z0-9-]{0,59}")) })
        require(definitions.map { it.spec.name }.distinct().size == definitions.size) { "Duplicate UI component" }
        require(definitions.none { it.spec.name in STATIC_COMPONENTS }) { "Cannot replace a static renderer" }
        require(
            definitions.all { definition ->
                RichComponentCatalog.components[definition.spec.name]?.let { it == definition.spec } != false
            }
        ) { "Existing protocol specifications must be preserved" }
        definitionsByName = Collections.unmodifiableMap(definitions.associateBy { it.spec.name })
        catalog = Collections.unmodifiableMap(
            RichComponentCatalog.components + definitions.associate { definition ->
                val spec = definition.spec
                spec.name to spec.copy(
                    attributes = Collections.unmodifiableMap(
                        spec.attributes.mapValues { (_, attribute) ->
                            attribute.copy(values = Collections.unmodifiableSet(attribute.values.toSet()))
                        }
                    ),
                    allowedChildren = Collections.unmodifiableSet(spec.allowedChildren.toSet())
                )
            }
        )
        interactionRegistry = RichInteractionRegistry(definitions.toList())
    }

    fun definition(name: String): RichUiComponentDefinition? = definitionsByName[name]

    private companion object {
        val STATIC_COMPONENTS = setOf("ui-card", "ui-row", "ui-col", "ui-metric", "ui-callout", "ui-steps", "ui-step")
    }
}

/** Intentionally empty until concrete components have an approved specification and renderer. */
object RichUiComponents {
    val registry = RichUiComponentRegistry()
}

val LocalRichUiComponentRegistry = staticCompositionLocalOf { RichUiComponents.registry }

/** Components emit typed local events; they cannot send a message or execute tools. */
class RichUiComponentScope internal constructor(
    val node: RichNode.Component,
    val state: RichInteractionSessionState?,
    val contentColor: Color,
    private val dispatch: (RichInteractionEvent) -> Unit,
    private val renderChildren: @Composable () -> Unit
) {
    val isEnabled: Boolean get() = state?.isInteractionEnabled == true

    fun setValue(key: String, value: RichInteractionValue) {
        val current = state?.takeIf { it.isInteractionEnabled } ?: return
        dispatch(RichInteractionEvent.SetValue(current.token, UUID.randomUUID().toString(), key, value))
    }

    fun invokeAction(key: String) {
        val current = state?.takeIf { it.isInteractionEnabled } ?: return
        dispatch(RichInteractionEvent.InvokeAction(current.token, UUID.randomUUID().toString(), key))
    }

    @Composable
    fun Children() = renderChildren()
}
