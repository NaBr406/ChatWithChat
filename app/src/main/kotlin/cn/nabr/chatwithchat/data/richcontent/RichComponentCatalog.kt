package cn.nabr.chatwithchat.data.richcontent

enum class AttributeKind { TEXT, ENUM, DECIMAL, INTEGER, REFERENCE_LIST }

data class AttributeSpec(
    val kind: AttributeKind = AttributeKind.TEXT,
    val required: Boolean = false,
    val values: Set<String> = emptySet()
)

data class ComponentSpec(
    val name: String,
    val attributes: Map<String, AttributeSpec>,
    val allowedChildren: Set<String> = emptySet(),
    val markdownChildren: Boolean = true,
    val selfClosing: Boolean = false
)

data class AttributeValidation(
    val accepted: Map<String, String>,
    val diagnostics: List<String>,
    val valid: Boolean
)

object RichComponentCatalog {
    private fun text(required: Boolean = false) = AttributeSpec(required = required)
    private fun enum(vararg values: String, required: Boolean = false) =
        AttributeSpec(AttributeKind.ENUM, required, values.toSet())
    private fun decimal(required: Boolean = false) = AttributeSpec(AttributeKind.DECIMAL, required)
    private fun integer(required: Boolean = false) = AttributeSpec(AttributeKind.INTEGER, required)
    private fun refs() = AttributeSpec(AttributeKind.REFERENCE_LIST)

    val components: Map<String, ComponentSpec> = listOf(
        ComponentSpec("ui-card", mapOf("key" to text(), "title" to text())),
        ComponentSpec("ui-row", mapOf("key" to text()), setOf("ui-col", "ui-metric")),
        ComponentSpec("ui-col", mapOf("key" to text())),
        ComponentSpec(
            "ui-metric",
            mapOf(
                "label" to text(true), "value" to text(true), "delta" to text(),
                "trend" to enum("up", "down", "neutral"),
                "tone" to enum("neutral", "success", "warning", "error")
            ),
            markdownChildren = false,
            selfClosing = true
        ),
        ComponentSpec("ui-callout", mapOf("tone" to enum("info", "success", "warning", "error"), "title" to text())),
        ComponentSpec("ui-steps", mapOf("key" to text()), setOf("ui-step")),
        ComponentSpec("ui-step", mapOf("key" to text(), "title" to text())),
        ComponentSpec("ui-segmented", mapOf("key" to text(true), "label" to text(true), "default" to text()), setOf("ui-option")),
        ComponentSpec("ui-option", mapOf("value" to text(true), "label" to text(true)), markdownChildren = false, selfClosing = true),
        ComponentSpec("ui-tabs", mapOf("key" to text(true), "default" to text()), setOf("ui-tab")),
        ComponentSpec("ui-tab", mapOf("value" to text(true), "label" to text(true))),
        ComponentSpec("ui-collapse", mapOf("key" to text(true), "title" to text(true), "default" to enum("closed", "open"))),
        ComponentSpec(
            "ui-slider",
            mapOf("key" to text(true), "label" to text(true), "min" to decimal(true), "max" to decimal(true),
                "step" to decimal(true), "default" to decimal(true), "unit" to text()),
            markdownChildren = false,
            selfClosing = true
        ),
        ComponentSpec("ui-value", mapOf("ref" to text(true)), markdownChildren = false, selfClosing = true),
        ComponentSpec("ui-button", mapOf("key" to text(true), "label" to text(true), "prompt" to text(true), "refs" to refs()), markdownChildren = false, selfClosing = true)
    ).associateBy(ComponentSpec::name)

    fun validate(name: String, attributes: Map<String, String>): AttributeValidation {
        val spec = components[name] ?: return AttributeValidation(emptyMap(), listOf("unknown-component"), false)
        val accepted = linkedMapOf<String, String>()
        val diagnostics = mutableListOf<String>()
        var valid = true
        attributes.forEach { (key, value) ->
            val rule = spec.attributes[key]
            if (rule == null) {
                diagnostics += "unknown-attribute:$key"
            } else if (!matches(rule, value)) {
                diagnostics += "invalid-attribute:$key"
                if (rule.required) valid = false
            } else {
                accepted[key] = value
            }
        }
        spec.attributes.filterValues(AttributeSpec::required).keys.forEach { key ->
            if (key !in attributes) {
                diagnostics += "missing-attribute:$key"
                valid = false
            }
        }
        if (name == "ui-slider" && valid) {
            val min = accepted.getValue("min").toDoubleOrNull()!!
            val max = accepted.getValue("max").toDoubleOrNull()!!
            val step = accepted.getValue("step").toDoubleOrNull()!!
            val default = accepted.getValue("default").toDoubleOrNull()!!
            if (!min.isFinite() || !max.isFinite() || !step.isFinite() || !default.isFinite() || max <= min || step <= 0 || default !in min..max) {
                diagnostics += "invalid-slider-range"
                valid = false
            } else if ((max - min) / step > 1000.0) {
                diagnostics += "slider-step-limit"
                valid = false
            }
        }
        return AttributeValidation(accepted, diagnostics, valid)
    }

    private fun matches(spec: AttributeSpec, value: String): Boolean = when (spec.kind) {
        AttributeKind.TEXT -> value.isNotBlank() || !spec.required
        AttributeKind.ENUM -> value in spec.values
        AttributeKind.DECIMAL -> value.toDoubleOrNull()?.isFinite() == true
        AttributeKind.INTEGER -> value.toIntOrNull() != null
        AttributeKind.REFERENCE_LIST -> value.split(',').all { it.trim().isNotEmpty() }
    }
}
