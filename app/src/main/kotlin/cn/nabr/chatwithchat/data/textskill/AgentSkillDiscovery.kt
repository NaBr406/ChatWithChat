package cn.nabr.chatwithchat.data.textskill

import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class DiscoveredSkillDirectory(
    val directory: File,
    val packageRelativeDir: String,
    val entryFile: File
)

data class DiscoveredSkillVariant(
    val directory: File,
    val packageRelativeDir: String,
    val entryFile: File,
    val variantKey: String,
    val isAdapterExtension: Boolean = false
)

interface SkillPackageAdapter {
    fun variants(root: File, discovered: List<DiscoveredSkillDirectory>): List<DiscoveredSkillVariant>
}

class AgentSkillDiscovery(
    private val adapters: List<SkillPackageAdapter> = listOf(DotSkillPackageAdapter())
) {
    fun discover(root: File): List<DiscoveredSkillVariant> {
        require(root.isDirectory) { "Skill package source is missing." }
        val directories = mutableListOf<DiscoveredSkillDirectory>()
        scan(root, root, directories)
        require(directories.isNotEmpty()) { "The ZIP does not contain a directory with SKILL.md." }
        val standard = directories.map { directory ->
            DiscoveredSkillVariant(
                directory = directory.directory,
                packageRelativeDir = directory.packageRelativeDir,
                entryFile = directory.entryFile,
                variantKey = "default"
            )
        }
        return (standard + adapters.flatMap { it.variants(root, directories) })
            .distinctBy { variant -> "${variant.packageRelativeDir.lowercase(Locale.ROOT)}:${variant.variantKey}" }
            .sortedWith(compareBy({ it.packageRelativeDir }, { it.variantKey }))
    }

    private fun scan(root: File, directory: File, found: MutableList<DiscoveredSkillDirectory>) {
        val children = directory.listFiles()?.sortedBy { it.name.lowercase(Locale.ROOT) }.orEmpty()
        val entry = children.firstOrNull { it.isFile && it.name.equals("SKILL.md", ignoreCase = true) }
        if (entry != null) {
            found += DiscoveredSkillDirectory(
                directory = directory,
                packageRelativeDir = root.relativePath(directory),
                entryFile = entry
            )
            return
        }
        children.filter(File::isDirectory).forEach { child -> scan(root, child, found) }
    }
}

class DotSkillPackageAdapter(
    private val json: Json = Json { ignoreUnknownKeys = true }
) : SkillPackageAdapter {
    override fun variants(root: File, discovered: List<DiscoveredSkillDirectory>): List<DiscoveredSkillVariant> =
        discovered.flatMap { skill -> variantsForDirectory(root, skill) }

    private fun variantsForDirectory(root: File, skill: DiscoveredSkillDirectory): List<DiscoveredSkillVariant> {
        val manifest = skill.directory.listFiles()
            ?.firstOrNull { it.isFile && it.name.equals("manifest.json", ignoreCase = true) }
            ?: return emptyList()
        val objectValue = runCatching { json.parseToJsonElement(manifest.readText(Charsets.UTF_8)).jsonObject }.getOrNull()
            ?: return emptyList()
        if (!objectValue.isDotSkillManifest()) return emptyList()
        val entries = objectValue["entrypoints"] as? JsonObject ?: return emptyList()
        return entries.entries.mapNotNull { (variantKey, value) ->
            val path = value.entryPath() ?: return@mapNotNull null
            val entry = skill.directory.resolveRelativeFile(path) ?: return@mapNotNull null
            if (!entry.isFile || !entry.extension.equals("md", ignoreCase = true)) return@mapNotNull null
            DiscoveredSkillVariant(
                directory = skill.directory,
                packageRelativeDir = root.relativePath(skill.directory),
                entryFile = entry,
                variantKey = variantKey,
                isAdapterExtension = true
            )
        }
    }
}

private fun JsonObject.isDotSkillManifest(): Boolean =
    this["manifest_version"]?.jsonPrimitive?.content == "1" &&
        this["kind"]?.jsonPrimitive?.content == "meta-skill" &&
        (this["engine"] as? JsonObject)?.get("name")?.jsonPrimitive?.content == "dot-skill"

private fun JsonElement.entryPath(): String? = when (this) {
    is kotlinx.serialization.json.JsonPrimitive -> runCatching { content }.getOrNull()
    is JsonObject -> this["path"]?.jsonPrimitive?.let { value -> runCatching { value.content }.getOrNull() }
        ?: this["file"]?.jsonPrimitive?.let { value -> runCatching { value.content }.getOrNull() }
    else -> null
}

internal fun File.relativePath(child: File): String = toPath().relativize(child.toPath())
    .toString()
    .replace(File.separatorChar, '/')
    .ifBlank { "." }

internal fun File.resolveRelativeFile(path: String): File? {
    val normalized = normalizedRelativePath(path) ?: return null
    val candidate = File(this, normalized)
    return candidate.takeIf { it.toPath().normalize().startsWith(toPath().normalize()) }
}

internal fun normalizedRelativePath(path: String): String? {
    val normalized = Normalizer.normalize(path.replace('\\', '/').trim(), Normalizer.Form.NFC)
    if (normalized.isEmpty() || normalized.startsWith('/') || normalized.contains("://") || Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
        return null
    }
    val segments = normalized.split('/')
    if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
    return segments.joinToString(separator = "/")
}
