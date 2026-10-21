package cn.nabr.chatwithchat.data.richcontent

object RichContentProjection {
    fun toMarkdown(document: RichDocument): String = renderNodes(document.nodes)

    fun toMarkdown(node: RichNode): String = renderNodes(listOf(node))

    private fun renderNodes(nodes: List<RichNode>): String = nodes.joinToString("") { node ->
        when (node) {
            is RichNode.Markdown -> node.text
            is RichNode.Invalid -> node.raw
            is RichNode.Incomplete -> node.raw
            is RichNode.Component -> renderComponent(node)
        }
    }

    private fun renderComponent(node: RichNode.Component): String {
        val body = renderNodes(node.children).trimEnd()
        val attrs = node.attributes
        val title = attrs["title"] ?: attrs["label"]
        return when (node.name) {
            "ui-card" -> section(title, body)
            "ui-callout" -> section(listOfNotNull(title, attrs["tone"]?.let { "[$it]" }).joinToString(" "), body)
            "ui-row" -> node.children.joinToString("\n\n") { child -> renderNodes(listOf(child)).trim() }.trim()
            "ui-col" -> body
            "ui-metric" -> buildString {
                append("**${attrs["label"].orEmpty()}:** ${attrs["value"].orEmpty()}")
                attrs["delta"]?.let { append(" ($it)") }
                attrs["trend"]?.let { append("; trend: $it") }
            }
            "ui-steps" -> {
                var stepNumber = 0
                node.children.mapNotNull { child ->
                    when (child) {
                        is RichNode.Component -> {
                            if (child.name != "ui-step") {
                                renderNodes(listOf(child)).trim().takeIf(String::isNotEmpty)
                            } else {
                                stepNumber++
                                "$stepNumber. ${child.attributes["title"]?.let { "**$it** " }.orEmpty()}${renderNodes(child.children).trim()}"
                            }
                        }
                        else -> renderNodes(listOf(child)).trim().takeIf(String::isNotEmpty)
                    }
                }.joinToString("\n")
            }
            "ui-step" -> listOfNotNull(title?.let { "**$it**" }, body.takeIf(String::isNotEmpty)).joinToString("\n\n")
            "ui-segmented" -> "${attrs["label"]?.let { "**$it**: " }.orEmpty()}" + node.children.filterIsInstance<RichNode.Component>().joinToString(" / ") { option ->
                val value = option.attributes["value"].orEmpty()
                val label = option.attributes["label"].orEmpty()
                if (value == attrs["default"]) "**$label**" else label
            }
            "ui-option" -> "${attrs["label"].orEmpty()} (${attrs["value"].orEmpty()})"
            "ui-tabs" -> node.children.filterIsInstance<RichNode.Component>().joinToString("\n\n") { tab ->
                "### ${tab.attributes["label"].orEmpty()}\n\n${renderNodes(tab.children).trim()}"
            }
            "ui-tab" -> section(title ?: attrs["value"], body)
            "ui-collapse" -> section(title, body)
            "ui-slider" -> "${attrs["label"]}: ${attrs["min"]}–${attrs["max"]} (default ${attrs["default"]}${attrs["unit"]?.let { " $it" }.orEmpty()})"
            "ui-value" -> "${attrs["ref"]} (default value)"
            "ui-button" -> "**${attrs["label"]}:** ${attrs["prompt"]}" + attrs["refs"]?.let { " (uses: $it)" }.orEmpty()
            else -> node.raw
        }
    }

    private fun section(title: String?, body: String): String = listOfNotNull(
        title?.takeIf(String::isNotBlank)?.let { "**$it**" },
        body.takeIf(String::isNotBlank)
    ).joinToString("\n\n")
}
