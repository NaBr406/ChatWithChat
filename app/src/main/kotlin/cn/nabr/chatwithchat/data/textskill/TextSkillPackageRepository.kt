package cn.nabr.chatwithchat.data.textskill

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class TextSkillInstallResult(
    val packageItem: InstalledSkillPackage,
    val replacedPackage: InstalledSkillPackage? = null
)

data class TextSkillInstallPreview(
    val previewId: String,
    val packageItem: InstalledSkillPackage,
    val replacedPackage: InstalledSkillPackage? = null
)

data class TextSkillContentPreview(
    val entryPath: String,
    val entryMarkdown: String,
    val references: List<TextSkillReference>
)

@Singleton
class TextSkillPackageRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val discovery = AgentSkillDiscovery()
    private val frontmatterParser = SkillFrontmatterParser()
    private val referenceResolver = TextSkillReferenceResolver()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val root = File(context.filesDir, "skills")
    private val registryFile = File(root, "registry.json")
    private val pendingInstalls = mutableMapOf<String, PendingInstall>()
    private val _packages = MutableStateFlow(loadRegistry())
    val packages: StateFlow<List<InstalledSkillPackage>> = _packages.asStateFlow()

    suspend fun install(uri: Uri): TextSkillInstallResult = withContext(Dispatchers.IO) {
        confirmInstall(prepareInstall(uri).previewId)
    }

    suspend fun install(input: InputStream, sourceName: String): TextSkillInstallResult = withContext(Dispatchers.IO) {
        confirmInstall(prepareInstall(input, sourceName).previewId)
    }

    suspend fun prepareInstall(uri: Uri): TextSkillInstallPreview = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            prepareInstall(input, uri.lastPathSegment ?: "skill.zip")
        } ?: throw IllegalArgumentException("Unable to open the selected ZIP.")
    }

    suspend fun prepareInstall(input: InputStream, sourceName: String): TextSkillInstallPreview = withContext(Dispatchers.IO) {
        mutex.withLock {
            root.mkdirs()
            val previewId = UUID.randomUUID().toString()
            val staging = File(root, ".preview-$previewId")
            val source = File(staging, "source")
            try {
                source.mkdirs()
                extractZip(input, source)
                val packageItem = buildPackage(source, sourceName, packageId = existingPackageId(sourceName) ?: UUID.randomUUID().toString())
                val existing = _packages.value.firstOrNull { it.packageId == packageItem.packageId }
                File(staging, "install.json").writeText(json.encodeToString(packageItem), Charsets.UTF_8)
                pendingInstalls[previewId] = PendingInstall(staging, packageItem, existing)
                TextSkillInstallPreview(previewId, packageItem, existing)
            } catch (error: Throwable) {
                staging.deleteRecursively()
                throw error
            }
        }
    }

    suspend fun confirmInstall(previewId: String): TextSkillInstallResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pending = pendingInstalls.remove(previewId)
                ?: throw IllegalStateException("The Skill import preview is no longer available.")
            try {
                val target = packageDirectory(pending.packageItem.packageId)
                val updated = (_packages.value.filterNot { it.packageId == pending.packageItem.packageId } + pending.packageItem)
                    .sortedBy { it.sourceName.lowercase(Locale.ROOT) }
                replaceDirectoryAndPersistRegistry(
                    staging = pending.directory,
                    target = target,
                    updatedRegistry = updated
                )
                _packages.value = updated
                TextSkillInstallResult(pending.packageItem, pending.replacedPackage)
            } catch (error: Throwable) {
                pending.directory.takeIf(File::exists)?.deleteRecursively()
                throw error
            }
        }
    }

    suspend fun discardInstallPreview(previewId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            pendingInstalls.remove(previewId)?.directory?.deleteRecursively()
        }
    }

    suspend fun delete(packageId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existing = _packages.value.firstOrNull { it.packageId == packageId } ?: return@withLock
            val target = packageDirectory(existing.packageId)
            val updated = _packages.value.filterNot { it.packageId == packageId }
            val backup = File(root, ".deleted-${UUID.randomUUID()}")
            if (target.exists()) moveAtomicallyOrReplace(target, backup)
            try {
                persistRegistry(updated)
                backup.deleteRecursively()
            } catch (error: Throwable) {
                if (backup.exists() && !target.exists()) moveAtomicallyOrReplace(backup, target)
                throw error
            }
            _packages.value = updated
        }
    }

    fun packageDirectory(packageId: String): File = File(root, packageId)

    fun resolveVariant(key: TextSkillVariantKey): Pair<InstalledSkillPackage, RegisteredTextSkillVariant>? {
        val packageItem = _packages.value.firstOrNull { it.packageId == key.packageId } ?: return null
        val variant = packageItem.variants.firstOrNull { it.skillId == key.skillId && it.variantKey == key.variantKey }
            ?: return null
        return packageItem to variant
    }

    suspend fun readContentPreview(key: TextSkillVariantKey): TextSkillContentPreview? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (packageItem, variant) = resolveVariant(key) ?: return@withLock null
            val sourceRoot = File(packageDirectory(packageItem.packageId), "source")
            val skillDirectory = if (variant.packageRelativeDir == ".") sourceRoot else {
                sourceRoot.resolveRelativeFile(variant.packageRelativeDir) ?: return@withLock null
            }
            val entry = sourceRoot.resolveRelativeFile(variant.entryPath) ?: return@withLock null
            if (!skillDirectory.isDirectory || !entry.isFile) return@withLock null
            val entryBytes = runCatching { entry.readBytes() }.getOrNull() ?: return@withLock null
            if (sha256(entryBytes) != variant.contentHash) return@withLock null
            val entryMarkdown = entryBytes.decodeUtf8OrNull() ?: return@withLock null
            val body = runCatching { frontmatterParser.parse(entryMarkdown).body }.getOrNull() ?: return@withLock null
            val references = referenceResolver.resolve(skillDirectory, body)
            if (!referencesMatchRegisteredVariant(variant, references)) return@withLock null
            TextSkillContentPreview(
                entryPath = variant.entryPath,
                entryMarkdown = entryMarkdown,
                references = references
            )
        }
    }

    private fun buildPackage(source: File, sourceName: String, packageId: String): InstalledSkillPackage {
        val variants = discovery.discover(source).map { discovered ->
            val entryBytes = discovered.entryFile.readBytes()
            require(entryBytes.size <= TextSkillLimits.MAX_ENTRY_BODY_BYTES) {
                "Skill entry ${discovered.entryFile.name} exceeds the text-mode limit."
            }
            val entry = entryBytes.decodeUtf8OrNull()
                ?: throw IllegalArgumentException("Skill entry ${discovered.entryFile.name} is not valid UTF-8 text.")
            val parsed = frontmatterParser.parse(entry)
            require(parsed.status != SkillStandardStatus.NON_STANDARD_FRONTMATTER) {
                "${discovered.entryFile.name} requires a description; standard Skills also require a name."
            }
            val references = referenceResolver.resolve(discovered.directory, parsed.body)
            val skillId = sha256("${discovered.packageRelativeDir}:${parsed.frontmatter.name ?: discovered.directory.name}").take(24)
            val name = parsed.frontmatter.name ?: discovered.directory.name
            val requestedCapabilities = requestedCapabilities(parsed.frontmatter, parsed.body)
            RegisteredTextSkillVariant(
                skillId = skillId,
                variantKey = discovered.variantKey,
                packageRelativeDir = discovered.packageRelativeDir,
                displayName = if (discovered.variantKey == "default") name else "$name / ${discovered.variantKey}",
                entryPath = source.relativePath(discovered.entryFile),
                name = name,
                description = requireNotNull(parsed.frontmatter.description),
                frontmatter = parsed.frontmatter,
                standardStatus = if (discovered.isAdapterExtension) SkillStandardStatus.ADAPTER_EXTENSION else parsed.status,
                textCompatibility = if (requestedCapabilities.isEmpty()) {
                    TextSkillCompatibility.TEXT_ONLY
                } else {
                    TextSkillCompatibility.TEXT_ONLY_WITH_UNAVAILABLE_CAPABILITIES
                },
                requestedCapabilities = requestedCapabilities,
                directReferencePaths = references.map(TextSkillReference::path),
                directReferenceContentHashes = references.associate { reference ->
                    reference.path to reference.contentHash
                },
                contentHash = sha256(entryBytes)
            )
        }
        require(variants.isNotEmpty()) { "The ZIP has no callable text Skill." }
        return InstalledSkillPackage(
            packageId = packageId,
            sourceName = sourceName.substringAfterLast('/').substringAfterLast('\\').take(200),
            variants = variants,
            hasScripts = source.walkTopDown().any { it.isDirectory && it.name.equals("scripts", ignoreCase = true) },
            requestedCapabilities = variants.flatMap(RegisteredTextSkillVariant::requestedCapabilities).distinct(),
            packageHash = directoryHash(source)
        )
    }

    private fun requestedCapabilities(frontmatter: SkillFrontmatter, body: String): List<SkillCapabilityRequest> = buildList {
        frontmatter.allowedTools.forEach { tool ->
            add(SkillCapabilityRequest(SkillCapability.HOST_TOOL_CATALOG, "frontmatter.allowed-tools", tool))
        }
        if (Regex("\\bmcp\\b", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            add(SkillCapabilityRequest(SkillCapability.MCP_TOOL_CALL, "skill body", "MCP declaration"))
        }
    }

    private fun extractZip(input: InputStream, target: File) {
        val archive = TextSkillZipArchiveValidator.readAndValidate(input)
        val seen = mutableSetOf<String>()
        var entryCount = 0
        var uncompressedBytes = 0L
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= TextSkillLimits.MAX_ZIP_ENTRIES) { "ZIP contains too many entries." }
                val entryPath = entry.name.replace('\\', '/').trimEnd('/')
                if (entryPath.isEmpty() && entry.isDirectory) continue
                val normalized = normalizedRelativePath(entryPath) ?: throw IllegalArgumentException("ZIP contains an unsafe path.")
                val pathKey = Normalizer.normalize(normalized, Normalizer.Form.NFC).lowercase(Locale.ROOT)
                require(seen.add(pathKey)) { "ZIP contains duplicate or case-conflicting paths." }
                val destination = target.resolveRelativeFile(normalized)
                    ?: throw IllegalArgumentException("ZIP entry escapes the package root.")
                if (entry.isDirectory) {
                    destination.mkdirs()
                    continue
                }
                destination.parentFile?.mkdirs()
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        uncompressedBytes += read
                        require(uncompressedBytes <= TextSkillLimits.MAX_UNCOMPRESSED_ZIP_BYTES) { "ZIP expands beyond the allowed size." }
                        require(destination.length() + read <= TextSkillLimits.MAX_SINGLE_FILE_BYTES) { "ZIP contains a file that is too large." }
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
        require(entryCount > 0) { "The selected ZIP is empty." }
    }

    private fun directoryHash(directory: File): String = directory.walkTopDown()
        .filter(File::isFile)
        .sortedBy { directory.relativePath(it) }
        .fold(MessageDigestAccumulator()) { accumulator, file ->
            accumulator.append(directory.relativePath(file))
            accumulator.append(file.readBytes())
            accumulator
        }
        .hash()

    private fun replaceDirectoryAndPersistRegistry(
        staging: File,
        target: File,
        updatedRegistry: List<InstalledSkillPackage>
    ) {
        val backup = File(root, ".backup-${UUID.randomUUID()}")
        if (target.exists()) moveAtomicallyOrReplace(target, backup)
        try {
            moveAtomicallyOrReplace(staging, target)
            persistRegistry(updatedRegistry)
            backup.deleteRecursively()
        } catch (error: Throwable) {
            if (target.exists()) target.deleteRecursively()
            if (backup.exists()) moveAtomicallyOrReplace(backup, target)
            throw error
        }
    }

    private fun moveAtomicallyOrReplace(source: File, target: File) {
        runCatching { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            .getOrElse { Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun existingPackageId(sourceName: String): String? = _packages.value
        .firstOrNull { it.sourceName.equals(sourceName.substringAfterLast('/').substringAfterLast('\\'), ignoreCase = true) }
        ?.packageId

    private fun loadRegistry(): List<InstalledSkillPackage> = runCatching {
        if (!registryFile.isFile) return@runCatching emptyList()
        json.decodeFromString<List<InstalledSkillPackage>>(registryFile.readText(Charsets.UTF_8))
            .filter { packageItem ->
                packageItem.packageId.isNotBlank() && packageDirectory(packageItem.packageId).isDirectory
            }
            // Older registries and interrupted/manual installs may contain duplicate rows. The UI
            // uses packageId as its stable LazyColumn key, so keep the first valid row instead of
            // allowing a corrupt registry to crash the Skills screen during composition.
            .distinctBy(InstalledSkillPackage::packageId)
            .map { packageItem ->
                packageItem.copy(
                    variants = packageItem.variants.distinctBy { variant ->
                        "${variant.skillId}:${variant.variantKey}"
                    }
                )
            }
    }.getOrDefault(emptyList())

    private fun persistRegistry(packages: List<InstalledSkillPackage>) {
        root.mkdirs()
        val temporary = File(root, ".registry-${UUID.randomUUID()}.tmp")
        temporary.writeText(json.encodeToString(packages), Charsets.UTF_8)
        moveAtomicallyOrReplace(temporary, registryFile)
    }

    private data class PendingInstall(
        val directory: File,
        val packageItem: InstalledSkillPackage,
        val replacedPackage: InstalledSkillPackage?
    )
}

private class MessageDigestAccumulator {
    private val digest = java.security.MessageDigest.getInstance("SHA-256")

    fun append(value: String) = apply {
        digest.update(value.toByteArray(Charsets.UTF_8))
        digest.update(0)
    }

    fun append(value: ByteArray) = apply {
        digest.update(value)
        digest.update(0)
    }

    fun hash(): String = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}
