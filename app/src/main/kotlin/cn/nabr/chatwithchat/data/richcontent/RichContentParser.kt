package cn.nabr.chatwithchat.data.richcontent

class RichContentParser(private val catalog: Map<String, ComponentSpec> = RichComponentCatalog.components) {
    private data class Frame(
        val name: String,
        val attrs: Map<String, String>,
        val start: Int,
        val openEnd: Int,
        val rawParts: StringBuilder = StringBuilder(),
        val children: MutableList<RichNode> = mutableListOf()
    )

    fun parse(source: String, isStreaming: Boolean = false, generation: Long = 0): RichDocument {
        val roots = mutableListOf<RichNode>()
        val diagnostics = mutableListOf<RichDiagnostic>()
        val stack = mutableListOf<Frame>()
        val interactionKeys = mutableSetOf<String>()
        var textStart = 0
        var i = 0
        var lineStart = true
        var fenceChar: Char? = null
        var fenceSize = 0
        var inlineTicks = 0
        var atIndentedCode = false

        fun targetChildren(): MutableList<RichNode> = if (stack.isEmpty()) roots else stack.last().children
        fun flushText(end: Int) {
            if (end > textStart) {
                val text = source.substring(textStart, end)
                targetChildren() += RichNode.Markdown(text, SourceRange(textStart, end))
            }
            textStart = end
        }
        fun appendRaw(start: Int, end: Int) { stack.forEach { it.rawParts.append(source, start, end) } }

        while (i < source.length) {
            val atLineStart = lineStart
            if (atLineStart || (inlineTicks == 0 && fenceChar == null &&
                    (source.startsWith("</ui-", i) || source.startsWith("<ui-", i)))) {
                val lineEnd = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                val line = source.substring(i, lineEnd)
                val trimmed = line.trimStart()
                val indent = line.length - trimmed.length
                val fenceMatch = if (atLineStart) Regex("^(`{3,}|~{3,})").find(trimmed) else null
                if (atLineStart && fenceChar != null) {
                    if (fenceMatch != null && fenceMatch.value.first() == fenceChar && fenceMatch.value.length >= fenceSize) fenceChar = null
                    val end = if (lineEnd < source.length) lineEnd + 1 else lineEnd
                    appendRaw(i, end); i = end; lineStart = true; continue
                }
                if (atLineStart && fenceMatch != null && inlineTicks == 0) {
                    fenceChar = fenceMatch.value.first(); fenceSize = fenceMatch.value.length
                    val end = if (lineEnd < source.length) lineEnd + 1 else lineEnd
                    appendRaw(i, end); i = end; lineStart = true; continue
                }
                if (atLineStart) atIndentedCode = indent >= 4 || trimmed.isEmpty() && atIndentedCode
                if (atLineStart && atIndentedCode) {
                    val end = if (lineEnd < source.length) lineEnd + 1 else lineEnd
                    appendRaw(i, end); i = end; lineStart = true; continue
                }
                val tokenStart = if (atLineStart) i + indent else i
                val token = parseTag(source, tokenStart)
                val looksLikeOpenTag = source.startsWith("<ui-", tokenStart)
                if (token != null) {
                    flushText(tokenStart)
                    appendRaw(tokenStart, token.end)
                    if (token.closing) {
                        val idx = stack.indexOfLast { it.name == token.name }
                        if (idx < 0 || idx != stack.lastIndex) {
                            val raw = source.substring(tokenStart, token.end)
                            targetChildren() += RichNode.Invalid(raw, "mismatched-close", SourceRange(tokenStart, token.end))
                            diagnostics += RichDiagnostic("mismatched-close", SourceRange(tokenStart, token.end))
                        } else {
                            val frame = stack.removeAt(idx)
                            val end = token.end
                            val content = frame.children.toList()
                            val validation = validate(frame.name, frame.attrs)
                            val spec = catalog[frame.name]
                            val directComponents = content.filterIsInstance<RichNode.Component>()
                            val childViolation = directComponents.any { child ->
                                spec?.allowedChildren?.isNotEmpty() == true && child.name !in spec.allowedChildren
                            } || (spec?.selfClosing == true) || (frame.name == "ui-row" &&
                                directComponents.count { it.name in spec?.allowedChildren.orEmpty() } !in 1..3)
                            val childInvalid = content.any { it is RichNode.Invalid }
                            val duplicateKey = duplicateInteractionKey(frame.name, validation.accepted, interactionKeys)
                            if (!validation.valid || childInvalid || childViolation || duplicateKey != null) {
                                val raw = source.substring(frame.start, end)
                                val reason = validation.diagnostics.joinToString().ifEmpty {
                                    duplicateKey?.let { "duplicate-key:$it" } ?: "invalid-child"
                                }
                                targetChildren() += RichNode.Invalid(raw, reason, SourceRange(frame.start, end))
                                diagnostics += validation.diagnostics.map { RichDiagnostic(it.substringBefore(':'), SourceRange(frame.start, end), it) }
                                if (childViolation) diagnostics += RichDiagnostic("invalid-child", SourceRange(frame.start, end))
                                duplicateKey?.let { key ->
                                    diagnostics += RichDiagnostic("duplicate-key", SourceRange(frame.start, end), key)
                                }
                            } else {
                                validation.accepted["key"]?.let(interactionKeys::add)
                                targetChildren() += RichNode.Component(frame.name, validation.accepted, content.toList(), source.substring(frame.start, end), SourceRange(frame.start, end))
                                diagnostics += validation.diagnostics.filter { it.startsWith("unknown-attribute") }.map { RichDiagnostic("unknown-attribute", SourceRange(frame.start, end), it) }
                            }
                        }
                    } else if (token.selfClosing) {
                        val validation = validate(token.name, token.attributes)
                        val duplicateKey = duplicateInteractionKey(token.name, validation.accepted, interactionKeys)
                        if (validation.valid && duplicateKey == null) {
                            validation.accepted["key"]?.let(interactionKeys::add)
                            targetChildren() += RichNode.Component(token.name, validation.accepted, emptyList(), source.substring(tokenStart, token.end), SourceRange(tokenStart, token.end))
                        }
                        else {
                            val reason = validation.diagnostics.joinToString().ifEmpty {
                                duplicateKey?.let { "duplicate-key:$it" }.orEmpty()
                            }
                            targetChildren() += RichNode.Invalid(source.substring(tokenStart, token.end), reason, SourceRange(tokenStart, token.end))
                            diagnostics += validation.diagnostics.map { RichDiagnostic(it.substringBefore(':'), SourceRange(tokenStart, token.end), it) }
                            duplicateKey?.let { key ->
                                diagnostics += RichDiagnostic("duplicate-key", SourceRange(tokenStart, token.end), key)
                            }
                        }
                    } else if (token.name !in catalog || stack.size >= 8 || catalog[token.name]?.selfClosing == true) {
                        targetChildren() += RichNode.Invalid(source.substring(tokenStart, token.end), if (token.name !in catalog) "unknown-component" else "nesting-limit", SourceRange(tokenStart, token.end))
                        val code = when {
                            token.name !in catalog -> "unknown-component"
                            catalog[token.name]?.selfClosing == true -> "unexpected-container"
                            else -> "nesting-limit"
                        }
                        diagnostics += RichDiagnostic(code, SourceRange(tokenStart, token.end))
                    } else {
                        stack += Frame(token.name, token.attributes, tokenStart, token.end)
                    }
                    i = token.end
                    textStart = i
                    lineStart = atLineStart
                    continue
                }
                if (looksLikeOpenTag) {
                    flushText(tokenStart)
                    val raw = source.substring(tokenStart)
                    val name = raw.substringAfter("<").takeWhile { it.isLetterOrDigit() || it == '-' }.ifEmpty { null }
                    val range = SourceRange(tokenStart, source.length)
                    targetChildren() += if (isStreaming) RichNode.Incomplete(raw, name, range)
                    else RichNode.Invalid(raw, "malformed-component-tag", range)
                    diagnostics += RichDiagnostic(if (isStreaming) "incomplete-component" else "malformed-component-tag", range)
                    i = source.length
                    textStart = i
                    break
                }
            }
            val c = source[i]
            if (c == '`') {
                var end = i
                while (end < source.length && source[end] == '`') end++
                val count = end - i
                inlineTicks = if (inlineTicks == 0) count else if (count == inlineTicks) 0 else inlineTicks
                i = end; lineStart = false; continue
            }
            if (c == '\n') lineStart = true else if (!c.isWhitespace()) lineStart = false
            i++
        }
        flushText(source.length)
        if (stack.isNotEmpty()) {
            val frame = stack.first()
            val raw = source.substring(frame.start)
            val range = SourceRange(frame.start, source.length)
            roots.removeAll { it.range.start >= frame.start }
            if (isStreaming) roots += RichNode.Incomplete(raw, frame.name, range)
            else roots += RichNode.Invalid(raw, "unclosed-component", range)
            diagnostics += RichDiagnostic(if (isStreaming) "incomplete-component" else "unclosed-component", range)
        }
        return RichDocument(source, normalizeTree(roots, source), diagnostics.toList(), generation, isStreaming)
    }

    private data class Tag(
        val name: String,
        val attributes: Map<String, String>,
        val closing: Boolean,
        val selfClosing: Boolean,
        val end: Int
    )

    private fun parseTag(text: String, start: Int): Tag? {
        if (text.getOrNull(start) != '<') return null
        var i = start + 1
        val closing = text.getOrNull(i) == '/'
        if (closing) i++
        val nameStart = i
        while (text.getOrNull(i)?.let { it.isLetterOrDigit() || it == '-' } == true) i++
        val name = text.substring(nameStart, i)
        if (!name.startsWith("ui-")) return null
        if (closing) {
            while (text.getOrNull(i)?.isWhitespace() == true) i++
            if (text.getOrNull(i) != '>') return null
            return Tag(name, emptyMap(), true, false, i + 1)
        }
        val attrs = linkedMapOf<String, String>()
        while (i < text.length) {
            while (text.getOrNull(i)?.isWhitespace() == true) i++
            if (text.getOrNull(i) == '>') return Tag(name, attrs, false, false, i + 1)
            if (text.getOrNull(i) == '/' && text.getOrNull(i + 1) == '>') return Tag(name, attrs, false, true, i + 2)
            val keyStart = i
            while (text.getOrNull(i)?.let { it.isLetterOrDigit() || it == '-' || it == '_' } == true) i++
            if (i == keyStart) return null
            val key = text.substring(keyStart, i)
            while (text.getOrNull(i)?.isWhitespace() == true) i++
            if (text.getOrNull(i++) != '=') return null
            while (text.getOrNull(i)?.isWhitespace() == true) i++
            if (text.getOrNull(i++) != '"') return null
            val valueStart = i
            while (i < text.length && text[i] != '"') i++
            if (i >= text.length) return null
            attrs[key] = decodeEntities(text.substring(valueStart, i))
            i++
        }
        return null
    }

    private fun decodeEntities(value: String): String = value
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")

    private fun validate(name: String, attributes: Map<String, String>): AttributeValidation {
        val spec = catalog[name] ?: return AttributeValidation(emptyMap(), listOf("unknown-component"), false)
        val accepted = linkedMapOf<String, String>()
        val errors = mutableListOf<String>()
        var valid = true
        attributes.forEach { (key, value) ->
            val rule = spec.attributes[key]
            if (rule == null) errors += "unknown-attribute:$key"
            else if (!matches(rule, value)) {
                errors += "invalid-attribute:$key"
                if (rule.required) valid = false
            } else accepted[key] = value
        }
        spec.attributes.filterValues(AttributeSpec::required).keys.filterNot(attributes::containsKey).forEach { key ->
            errors += "missing-attribute:$key"
            valid = false
        }
        if (name == "ui-slider" && valid) {
            val numbers = listOf("min", "max", "step", "default").map { accepted.getValue(it).toDoubleOrNull()!! }
            val (min, max, step, default) = numbers
            if (!min.isFinite() || !max.isFinite() || !step.isFinite() || !default.isFinite() || max <= min || step <= 0 || default !in min..max) {
                errors += "invalid-slider-range"
                valid = false
            } else if ((max - min) / step > 1000.0) {
                errors += "slider-step-limit"
                valid = false
            }
        }
        return AttributeValidation(accepted, errors, valid)
    }

    private fun matches(spec: AttributeSpec, value: String): Boolean = when (spec.kind) {
        AttributeKind.TEXT -> value.isNotBlank() || !spec.required
        AttributeKind.ENUM -> value in spec.values
        AttributeKind.DECIMAL -> value.toDoubleOrNull()?.isFinite() == true
        AttributeKind.INTEGER -> value.toIntOrNull() != null
        AttributeKind.REFERENCE_LIST -> value.split(',').all { it.trim().isNotEmpty() }
    }

    private fun duplicateInteractionKey(
        name: String,
        attributes: Map<String, String>,
        usedKeys: Set<String>
    ): String? {
        if (name !in INTERACTIVE_COMPONENTS) return null
        val key = attributes["key"] ?: return null
        return key.takeIf(usedKeys::contains)
    }

    private fun normalizeTree(nodes: List<RichNode>, source: String): List<RichNode> {
        if (nodes.isNotEmpty()) return nodes
        return if (source.isEmpty()) emptyList() else listOf(RichNode.Markdown(source, SourceRange(0, source.length)))
    }
}

private val INTERACTIVE_COMPONENTS = setOf(
    "ui-segmented",
    "ui-tabs",
    "ui-collapse",
    "ui-slider",
    "ui-button"
)
