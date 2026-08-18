package cn.nabr.chatwithchat.data.textskill

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

data class TextSkillReference(
    val path: String,
    val content: String,
    val contentHash: String
)

class TextSkillReferenceResolver {
    fun resolve(skillDirectory: File, body: String): List<TextSkillReference> {
        val paths = linkedPaths(body)
            .mapNotNull(::normalizedRelativePath)
            .distinct()
            .sorted()
        require(paths.size <= TextSkillLimits.MAX_DIRECT_REFERENCES_PER_SKILL) {
            "A Skill may directly reference at most ${TextSkillLimits.MAX_DIRECT_REFERENCES_PER_SKILL} text files."
        }
        return paths.mapNotNull { path ->
            if (!path.isAllowedTextReference()) return@mapNotNull null
            val file = skillDirectory.resolveRelativeFile(path) ?: return@mapNotNull null
            if (!file.isFile || file.length() > TextSkillLimits.MAX_REFERENCE_FILE_BYTES) return@mapNotNull null
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@mapNotNull null
            val text = bytes.decodeUtf8OrNull() ?: return@mapNotNull null
            TextSkillReference(path, text, sha256(bytes))
        }
    }

    private fun linkedPaths(body: String): List<String> = buildList {
        MARKDOWN_LINK.findAll(body).forEach { match -> add(match.groupValues[1].substringBefore('#').trim()) }
        BACKTICK_PATH.findAll(body).forEach { match -> add(match.groupValues[1].trim()) }
    }

    private companion object {
        val MARKDOWN_LINK = Regex("\\]\\(([^)]+)\\)")
        val BACKTICK_PATH = Regex("`([^`\\n]+(?:/|\\\\)[^`\\n]+)`")
    }
}

private fun String.isAllowedTextReference(): Boolean {
    val extension = substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension !in setOf("sh", "bash", "zsh", "bat", "cmd", "ps1", "py", "js", "ts", "exe", "dll", "so", "dylib", "jar", "apk")
}

internal fun ByteArray.decodeUtf8OrNull(): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
        .toString()
        .takeUnless { it.indexOf('\u0000') >= 0 }
} catch (_: CharacterCodingException) {
    null
}
