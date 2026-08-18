package cn.nabr.chatwithchat.data.textskill

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

data class ParsedSkillMarkdown(
    val frontmatter: SkillFrontmatter,
    val body: String,
    val status: SkillStandardStatus
)

class SkillFrontmatterParser(
    private val yaml: Yaml = Yaml(SafeConstructor(LoaderOptions()))
) {
    fun parse(markdown: String): ParsedSkillMarkdown {
        val normalized = markdown.removePrefix("\uFEFF").replace("\r\n", "\n")
        require(normalized.startsWith("---\n")) { "SKILL.md must start with YAML frontmatter." }
        val closing = normalized.indexOf("\n---\n", startIndex = 4)
        require(closing >= 0) { "SKILL.md YAML frontmatter is not closed." }
        val rawFrontmatter = normalized.substring(4, closing)
        val body = normalized.substring(closing + 5)
        val values = (yaml.load<Any?>(rawFrontmatter) as? Map<*, *>)
            ?.entries
            ?.associate { (key, value) -> key.toString() to value }
            ?: throw IllegalArgumentException("SKILL.md YAML frontmatter must be a mapping.")
        val name = values["name"]?.asNonBlankString()
        val description = values["description"]?.asNonBlankString()
        val allowedTools = values["allowed-tools"].asStringList()
        val metadata = (values["metadata"] as? Map<*, *>)
            ?.entries
            ?.associate { (key, value) -> key.toString() to value.toString() }
            .orEmpty()
        val frontmatter = SkillFrontmatter(
            name = name,
            description = description,
            license = values["license"]?.asNonBlankString(),
            compatibility = values["compatibility"]?.asNonBlankString(),
            metadata = metadata,
            allowedTools = allowedTools,
            userInvocable = values["user-invocable"] as? Boolean
        )
        val status = when {
            name != null && description != null -> SkillStandardStatus.STANDARD
            name == null && description != null -> SkillStandardStatus.CLAUDE_COMPATIBLE
            else -> SkillStandardStatus.NON_STANDARD_FRONTMATTER
        }
        return ParsedSkillMarkdown(frontmatter, body, status)
    }
}

private fun Any?.asNonBlankString(): String? = (this as? String)?.trim()?.takeIf { it.isNotEmpty() }

private fun Any?.asStringList(): List<String> = when (this) {
    is String -> split(',').map { it.trim() }.filter { it.isNotBlank() }
    is List<*> -> mapNotNull { it.asNonBlankString() }
    else -> emptyList()
}
