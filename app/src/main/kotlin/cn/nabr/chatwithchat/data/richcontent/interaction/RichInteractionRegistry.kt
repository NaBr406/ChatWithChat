package cn.nabr.chatwithchat.data.richcontent.interaction

import cn.nabr.chatwithchat.data.richcontent.RichNode

/** Adapters are trusted application code; a registry registers no components by default. */
interface RichInteractionBindingAdapter {
    val componentName: String

    fun bind(node: RichNode.Component): RichInteractionBinding?
}

class RichInteractionRegistry(adapters: List<RichInteractionBindingAdapter> = emptyList()) {
    private val adaptersByName: Map<String, RichInteractionBindingAdapter>

    init {
        require(adapters.size <= 256)
        require(adapters.all { it.componentName.matches(Regex("[a-z][a-z0-9-]{0,63}")) })
        require(adapters.map { it.componentName }.distinct().size == adapters.size) { "Duplicate component adapter" }
        adaptersByName = adapters.associateBy { it.componentName }
    }

    internal fun bind(node: RichNode.Component): RichInteractionBinding? = adaptersByName[node.name]?.bind(node)
}
