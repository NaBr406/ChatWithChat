package cn.nabr.chatwithchat.data.richcontent

/** Builds the bounded protocol section included in a normal answer prompt. */
class RichContentPromptBuilder(
    private val catalog: Map<String, ComponentSpec> = RichComponentCatalog.components
) {
    fun build(): String = buildString {
        appendLine("Native rich UI is available when it improves the answer.")
        appendLine("Use ordinary Markdown for ordinary answers; do not force a component into every answer.")
        appendLine("Only use the exact lower-case ui-* tags listed below. Tags must occupy their own logical line, use double-quoted attributes, and be well formed.")
        appendLine("Markdown remains valid inside container components. Do not interpret tags inside fenced, inline, or indented code as UI.")
        appendLine("Do not emit HTML, CSS, JavaScript, arbitrary expressions, remote UI, or components outside this catalog.")
        appendLine("Displayed values are model-provided text and are not client-verified. Keep all content readable as Markdown if a component cannot be used.")
        appendLine("Interactive controls are not advertised until the host provides local state and action callbacks; use Markdown for choices and actions for now.")
        appendLine("Available components:")
        catalog.values
            .filter { it.name in ADVERTISED_COMPONENTS }
            .sortedBy(ComponentSpec::name)
            .forEach { spec ->
                append("- <${spec.name}")
                spec.attributes.forEach { (name, attribute) ->
                    append(" ")
                    if (!attribute.required) append("[")
                    append(name)
                    append("=\"${attributeDescription(attribute)}\"")
                    if (!attribute.required) append("]")
                }
                if (spec.selfClosing) {
                    append(" />")
                } else {
                    append("> … </${spec.name}>")
                }
                if (spec.allowedChildren.isNotEmpty()) {
                    append("; children: ")
                    append(spec.allowedChildren.sorted().joinToString(", "))
                }
                appendLine()
            }
        appendLine("For interactive components, keep keys unique within this answer and use only the declared attributes. Never claim that a button action has already run.")
    }

    private fun attributeDescription(attribute: AttributeSpec): String = when (attribute.kind) {
        AttributeKind.TEXT -> "text"
        AttributeKind.ENUM -> attribute.values.sorted().joinToString("|", prefix = "{", postfix = "}")
        AttributeKind.DECIMAL -> "number"
        AttributeKind.INTEGER -> "integer"
        AttributeKind.REFERENCE_LIST -> "comma-separated-keys"
    }

    private companion object {
        val ADVERTISED_COMPONENTS = setOf(
            "ui-card",
            "ui-row",
            "ui-col",
            "ui-metric",
            "ui-callout",
            "ui-steps",
            "ui-step"
        )
    }
}
