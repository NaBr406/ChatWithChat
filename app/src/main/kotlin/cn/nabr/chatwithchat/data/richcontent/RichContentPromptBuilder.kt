package cn.nabr.chatwithchat.data.richcontent

import cn.nabr.chatwithchat.data.richcontent.openui.OpenUiPromptContract

/** The prompt contract matches the bundled 55-component OpenUI library. */
class RichContentPromptBuilder(
    @Suppress("UNUSED_PARAMETER") catalog: Map<String, ComponentSpec> = RichComponentCatalog.components
) {
    fun buildJsonAnswerContentPrompt(): String = buildString {
        appendLine("Answer formatting within the tool JSON protocol:")
        appendLine("These formatting rules apply ONLY to the decoded final_answer.content string. Preserve the required outer JSON envelope and tool_calls protocol.")
        appendLine(build())
        appendLine("Return one valid JSON object, never raw Markdown or OpenUI outside it. JSON-escape all quotes and newlines within content.")
        append("JSON envelope example: ")
        append("""{"type":"final_answer","content":"Here is the summary.\n\n```openui\nroot = Col([Text(\"Summary\")])\n```"}""")
    }

    fun build(): String = buildString {
        appendLine("Answer in ordinary Markdown. When an interactive tool, comparison, visualization, or composed page helps, add an OpenUI Lang 1.0 program inside a fenced block whose exact language is openui.")
        appendLine("Always include a self-contained semantic Markdown explanation OUTSIDE every openui fence, before the first fence. Include the answer's actual facts, recommendations, data, labels and important caveats there. Copy, conversation history, memory and export retain only that explanation; OpenUI source is presentation data and is never context. A UI-only answer is invalid.")
        appendLine("The contract below applies ONLY INSIDE openui fences. Do not output the historical ui-* XML protocol. Match the user's language. Use the listed component and function signatures inside a fence. The OpenUI renderer cannot execute chat tools or Query. SearchImage displays images in the UI. Determine chat tool availability from the tools offered for this request, including any discovery function.")
        appendLine(OpenUiPromptContract.text)
        appendLine("Host integration rules override any conflicting examples above: preserve the required semantic Markdown explanation outside the fence. A plain answer needs no UI fence. Use root = Col([...]) and only the 55 listed components within a fence.")
        appendLine("Local variables and input state stay on the device and are not automatically supplied to later answers. Do not claim photos, maps, URLs or remote resources were verified; external resources may be unavailable. Prefer supplied verified URLs and helpful fallbacks.")
        appendLine("@ToAssistant is a user-clicked request to populate an EMPTY draft composer for review; it NEVER sends automatically. Explicitly include any chosen form values needed for that new request in its short message. @OpenUrl accepts only http(s); @CopyToClipboard copies only user-selected content. These are the only native actions an OpenUI component can trigger. HtmlView runs in an isolated sandbox with no access to native actions.")
        appendLine("Example decoded answer:\nThe plan takes two steps: preserve the original, then verify a restored copy.\n\n```openui\nroot = Col([Title(\"Backup plan\", \"lg\"), List([\"Preserve the original\", \"Verify a restored copy\"], \"number\")])\n```")
    }
}
