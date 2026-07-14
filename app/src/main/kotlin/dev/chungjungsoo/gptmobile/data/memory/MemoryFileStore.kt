package dev.chungjungsoo.gptmobile.data.memory

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MemoryFileStore(
    private val paths: MemoryFilePaths,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val revisionGate = Any()
    private val _longTermRevision = MutableStateFlow(0L)
    val longTermRevision: StateFlow<Long> = _longTermRevision.asStateFlow()
    private var maintenanceRevision = 0L

    fun ensureStore(): Result<MemoryFileStoreSnapshot> = runCatching {
        synchronized(revisionGate) {
            ensureDirectories()
            val longTermFile = ensureLongTermMemoryFile()
            val todayFile = ensureDailyMemoryFile(LocalDate.now(clock))
            MemoryFileStoreSnapshot(
                rootDirectory = paths.rootDirectory,
                longTermMemoryFile = longTermFile,
                todayMemoryFile = todayFile
            )
        }
    }

    fun readLongTermMemory(): Result<String> = runCatching {
        synchronized(revisionGate) {
            ensureDirectories()
            ensureLongTermMemoryFile().readText(StandardCharsets.UTF_8)
        }
    }

    fun readDailyMemory(date: LocalDate = LocalDate.now(clock)): Result<String> = runCatching {
        synchronized(revisionGate) {
            ensureDirectories()
            ensureDailyMemoryFile(date).readText(StandardCharsets.UTF_8)
        }
    }

    fun readMemoryFile(file: File): Result<String> = runCatching {
        synchronized(revisionGate) {
            ensureDirectories()
            file.readText(StandardCharsets.UTF_8)
        }
    }

    fun listMemoryFiles(): Result<List<File>> = runCatching {
        synchronized(revisionGate) {
            managedMemoryFiles(includeDaily = true)
        }
    }

    internal fun readCorpusFiles(corpus: MemoryCorpus): Result<MemoryFileCorpusRead> = runCatching {
        synchronized(revisionGate) {
            val files = managedMemoryFiles(includeDaily = corpus == MemoryCorpus.MAINTENANCE_WORKING_SET)
            MemoryFileCorpusRead(
                revision = revisionFor(corpus),
                files = files.map { file ->
                    MemoryFileContent(
                        sourcePath = paths.relativePath(file),
                        bytes = Files.readAllBytes(file.toPath())
                    )
                }
            )
        }
    }

    fun relativePath(file: File): Result<String> = runCatching {
        paths.relativePath(file)
    }

    fun currentMemoryFileHash(sourcePath: String): Result<String> = runCatching {
        synchronized(revisionGate) {
            ensureDirectories()
            val sourceFile = paths.memoryFile(sourcePath)
            if (!sourceFile.isFile) {
                error("Memory file does not exist: ${sourceFile.absolutePath}")
            }
            sha256(Files.readAllBytes(sourceFile.toPath()))
        }
    }

    fun appendDailyNote(
        text: String,
        date: LocalDate = LocalDate.now(clock)
    ): Result<File> = runCatching {
        synchronized(revisionGate) {
            ensureDirectories()
            val dailyFile = ensureDailyMemoryFile(date)
            val block = normalizeAppendedBlock(text)
            if (block.isNotEmpty()) {
                dailyFile.appendText(block, StandardCharsets.UTF_8)
                advanceRevisionFor(dailyFile)
            }
            dailyFile
        }
    }

    fun appendLongTermMemory(text: String): Result<File> = runCatching {
        synchronized(revisionGate) {
            ensureDirectories()
            val longTermFile = ensureLongTermMemoryFile()
            val block = normalizeAppendedBlock(text)
            if (block.isNotEmpty()) {
                longTermFile.appendText(block, StandardCharsets.UTF_8)
                advanceRevisionFor(longTermFile)
            }
            longTermFile
        }
    }

    fun replaceLongTermMemory(content: String): Result<MemoryFileReplacement> = runCatching {
        synchronized(revisionGate) {
            ensureDirectories()
            val target = ensureLongTermMemoryFile()
            replaceMemoryFileInternal(target, content)
        }
    }

    fun replaceMemoryFile(file: File, content: String): Result<MemoryFileReplacement> = runCatching {
        synchronized(revisionGate) {
            ensureDirectories()
            val target = requireManagedMemoryFile(file)
            if (!target.exists()) error("Memory file does not exist: ${target.absolutePath}")
            replaceMemoryFileInternal(target, content)
        }
    }

    fun stageMemoryFile(
        sourcePath: String,
        content: String,
        stagingId: String
    ): Result<StagedMemoryFile> = runCatching {
        synchronized(revisionGate) {
            ensureDirectories()
            val sourceFile = paths.memoryFile(sourcePath)
            if (!sourceFile.isFile) {
                error("Memory file does not exist: ${sourceFile.absolutePath}")
            }
            val targetBytes = normalizeFullFileContent(content).toByteArray(StandardCharsets.UTF_8)
            val targetHash = sha256(targetBytes)
            val stagedTargetFile = paths.stagedTargetFile(stagingId)
            if (stagedTargetFile.exists()) {
                if (!stagedTargetFile.isFile) {
                    error("Expected staged target file: ${stagedTargetFile.absolutePath}")
                }
                check(sha256(Files.readAllBytes(stagedTargetFile.toPath())) == targetHash) {
                    "Staging ID already contains a different target"
                }
            } else {
                writeBytesAtomically(stagedTargetFile, targetBytes)
            }
            check(sha256(Files.readAllBytes(stagedTargetFile.toPath())) == targetHash) {
                "Staged target hash verification failed"
            }
            StagedMemoryFile(
                sourcePath = sourcePath,
                stagedTargetPath = paths.relativePath(stagedTargetFile),
                baseSourceHash = sha256(Files.readAllBytes(sourceFile.toPath())),
                targetSourceHash = targetHash
            )
        }
    }

    fun commitStagedMemoryFile(stagedMemoryFile: StagedMemoryFile): Result<MemoryFileCommitOutcome> =
        commitStagedMemoryFile(
            sourcePath = stagedMemoryFile.sourcePath,
            stagedTargetPath = stagedMemoryFile.stagedTargetPath,
            baseSourceHash = stagedMemoryFile.baseSourceHash,
            targetSourceHash = stagedMemoryFile.targetSourceHash
        )

    fun commitStagedMemoryFile(
        sourcePath: String,
        stagedTargetPath: String,
        baseSourceHash: String,
        targetSourceHash: String
    ): Result<MemoryFileCommitOutcome> = runCatching {
        synchronized(revisionGate) {
            ensureDirectories(includeStaging = false)
            val expectedBaseHash = requireSha256(baseSourceHash)
            val expectedTargetHash = requireSha256(targetSourceHash)
            val target = paths.memoryFile(sourcePath)
            if (!target.isFile) {
                error("Memory file does not exist: ${target.absolutePath}")
            }
            val currentHash = sha256(Files.readAllBytes(target.toPath()))
            when {
                currentHash == expectedTargetHash -> MemoryFileCommitOutcome.AlreadyCommitted(
                    file = target,
                    currentSourceHash = currentHash
                )
                currentHash != expectedBaseHash -> MemoryFileCommitOutcome.Conflict(
                    file = target,
                    currentSourceHash = currentHash,
                    expectedBaseSourceHash = expectedBaseHash,
                    targetSourceHash = expectedTargetHash
                )
                else -> {
                    val stagedTarget = try {
                        paths.stagedTargetFileFromPath(stagedTargetPath)
                    } catch (_: IllegalArgumentException) {
                        return@synchronized MemoryFileCommitOutcome.UnrecoverableStaging(
                            file = target,
                            currentSourceHash = currentHash,
                            reason = MEMORY_MUTATION_UNRECOVERABLE_STAGING_INVALID
                        )
                    }
                    val stagedAttributes = try {
                        Files.readAttributes(stagedTarget.toPath(), BasicFileAttributes::class.java)
                    } catch (_: NoSuchFileException) {
                        val stagingDirectoryAttributes = try {
                            Files.readAttributes(paths.stagingDirectory.toPath(), BasicFileAttributes::class.java)
                        } catch (_: NoSuchFileException) {
                            return@synchronized MemoryFileCommitOutcome.UnrecoverableStaging(
                                file = target,
                                currentSourceHash = currentHash,
                                reason = MEMORY_MUTATION_UNRECOVERABLE_STAGING_MISSING
                            )
                        }
                        if (!stagingDirectoryAttributes.isDirectory) {
                            throw FileSystemException(
                                stagedTarget.path,
                                null,
                                "Memory staging parent is not a directory"
                            )
                        }
                        return@synchronized MemoryFileCommitOutcome.UnrecoverableStaging(
                            file = target,
                            currentSourceHash = currentHash,
                            reason = MEMORY_MUTATION_UNRECOVERABLE_STAGING_MISSING
                        )
                    }
                    if (!stagedAttributes.isRegularFile) {
                        return@synchronized MemoryFileCommitOutcome.UnrecoverableStaging(
                            file = target,
                            currentSourceHash = currentHash,
                            reason = MEMORY_MUTATION_UNRECOVERABLE_STAGING_INVALID
                        )
                    }
                    val stagedBytes = try {
                        Files.readAllBytes(stagedTarget.toPath())
                    } catch (_: NoSuchFileException) {
                        return@synchronized MemoryFileCommitOutcome.UnrecoverableStaging(
                            file = target,
                            currentSourceHash = currentHash,
                            reason = MEMORY_MUTATION_UNRECOVERABLE_STAGING_MISSING
                        )
                    }
                    if (sha256(stagedBytes) != expectedTargetHash) {
                        return@synchronized MemoryFileCommitOutcome.UnrecoverableStaging(
                            file = target,
                            currentSourceHash = currentHash,
                            reason = MEMORY_MUTATION_UNRECOVERABLE_STAGING_HASH_MISMATCH
                        )
                    }
                    val backup = backupMemoryFile(target)
                    writeBytesAtomically(target, stagedBytes)
                    val committedHash = sha256(Files.readAllBytes(target.toPath()))
                    check(committedHash == expectedTargetHash) {
                        "Committed memory file hash verification failed"
                    }
                    advanceRevisionFor(target)
                    MemoryFileCommitOutcome.Committed(
                        file = target,
                        currentSourceHash = committedHash,
                        backupFile = backup
                    )
                }
            }
        }
    }

    fun cleanupStagedTarget(stagedTargetPath: String): Result<Boolean> = runCatching {
        synchronized(revisionGate) {
            ensureDirectories()
            Files.deleteIfExists(paths.stagedTargetFileFromPath(stagedTargetPath).toPath())
        }
    }

    fun cleanupStagedTargets(stagingIdPrefix: String): Result<Int> = runCatching {
        synchronized(revisionGate) {
            ensureDirectories()
            paths.stagedTargetFiles(stagingIdPrefix).count { stagedTarget ->
                Files.deleteIfExists(stagedTarget.toPath())
            }
        }
    }

    fun restoreMemoryFile(replacement: MemoryFileReplacement): Result<File> = runCatching {
        synchronized(revisionGate) {
            ensureDirectories()
            val target = requireManagedMemoryFile(replacement.file)
            if (!replacement.backupFile.exists()) {
                error("Memory backup does not exist: ${replacement.backupFile.absolutePath}")
            }
            writeAtomically(target, replacement.backupFile.readText(StandardCharsets.UTF_8))
            advanceRevisionFor(target)
            target
        }
    }

    private fun replaceMemoryFileInternal(target: File, content: String): MemoryFileReplacement {
        val backup = backupMemoryFile(target)
        writeAtomically(target, normalizeFullFileContent(content))
        advanceRevisionFor(target)
        return MemoryFileReplacement(
            file = target,
            backupFile = backup
        )
    }

    private fun managedMemoryFiles(includeDaily: Boolean): List<File> {
        ensureDirectories()
        return buildList {
            add(ensureLongTermMemoryFile())
            if (includeDaily && paths.dailyMemoryDirectory.exists()) {
                addAll(
                    paths.dailyMemoryDirectory
                        .listFiles { file -> file.isFile && file.extension.equals("md", ignoreCase = true) }
                        .orEmpty()
                        .sortedBy { it.name }
                )
            }
        }
    }

    private fun ensureDirectories(includeStaging: Boolean = true) {
        paths.rootDirectory.mkdirsOrThrow()
        paths.dailyMemoryDirectory.mkdirsOrThrow()
        paths.backupDirectory.mkdirsOrThrow()
        if (includeStaging) paths.stagingDirectory.mkdirsOrThrow()
    }

    private fun ensureLongTermMemoryFile(): File {
        val file = paths.longTermMemoryFile
        if (!file.exists()) {
            writeAtomically(file, LONG_TERM_MEMORY_HEADER)
            advanceRevisionFor(file)
        }
        return file
    }

    private fun ensureDailyMemoryFile(date: LocalDate): File {
        val file = paths.dailyMemoryFile(date)
        if (!file.exists()) {
            writeAtomically(file, dailyMemoryHeader(date))
            advanceRevisionFor(file)
        }
        return file
    }

    private fun revisionFor(corpus: MemoryCorpus): Long = when (corpus) {
        MemoryCorpus.CHAT_RECALL_LONG_TERM -> _longTermRevision.value
        MemoryCorpus.MAINTENANCE_WORKING_SET -> maintenanceRevision
    }

    private fun advanceRevisionFor(file: File) {
        val canonicalPath = file.canonicalFile.toPath()
        when {
            canonicalPath == paths.longTermMemoryFile.canonicalFile.toPath() -> {
                _longTermRevision.value += 1
                maintenanceRevision += 1
            }
            canonicalPath.startsWith(paths.dailyMemoryDirectory.canonicalFile.toPath()) -> {
                maintenanceRevision += 1
            }
        }
    }

    private fun backupMemoryFile(file: File): File {
        val timestamp = LocalDateTime.now(clock).format(BACKUP_TIMESTAMP_FORMATTER)
        val backup = File(paths.backupDirectory, "MEMORY.md.$timestamp.bak")
            .takeIf { file.canonicalFile == paths.longTermMemoryFile.canonicalFile }
            ?: File(paths.backupDirectory, "${file.name}.$timestamp.bak")
        Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return backup
    }

    private fun requireManagedMemoryFile(file: File): File {
        val rootPath = paths.rootDirectory.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        if (!filePath.startsWith(rootPath)) {
            error("Memory file is outside the managed store: ${file.absolutePath}")
        }
        return file.canonicalFile
    }

    private fun writeAtomically(file: File, content: String) {
        writeBytesAtomically(file, content.toByteArray(StandardCharsets.UTF_8))
    }

    private fun writeBytesAtomically(file: File, content: ByteArray) {
        file.parentFile?.mkdirsOrThrow()
        val tempFile = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        FileOutputStream(tempFile).use { output ->
            output.write(content)
            output.flush()
            output.fd.sync()
        }
        try {
            try {
                Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun File.mkdirsOrThrow() {
        if (!exists() && !mkdirs()) {
            error("Unable to create directory: $absolutePath")
        }
        if (!isDirectory) {
            error("Expected directory but found file: $absolutePath")
        }
    }

    private fun normalizeAppendedBlock(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return ""
        return "\n$trimmed\n"
    }

    private fun normalizeFullFileContent(content: String): String =
        content.trimEnd() + "\n"

    private fun requireSha256(value: String): String {
        require(SHA_256_REGEX.matches(value)) {
            "Expected a SHA-256 hash"
        }
        return value.lowercase()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun dailyMemoryHeader(date: LocalDate): String =
        "# ${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}\n\n"

    companion object {
        const val LONG_TERM_MEMORY_HEADER = "# ChatWithChat Memory\n\n"
        private val BACKUP_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val SHA_256_REGEX = Regex("[0-9a-fA-F]{64}")
    }
}

data class MemoryFileStoreSnapshot(
    val rootDirectory: File,
    val longTermMemoryFile: File,
    val todayMemoryFile: File
)

data class MemoryFileReplacement(
    val file: File,
    val backupFile: File
)

data class StagedMemoryFile(
    val sourcePath: String,
    val stagedTargetPath: String,
    val baseSourceHash: String,
    val targetSourceHash: String
)

sealed interface MemoryFileCommitOutcome {
    val file: File
    val currentSourceHash: String

    data class Committed(
        override val file: File,
        override val currentSourceHash: String,
        val backupFile: File
    ) : MemoryFileCommitOutcome

    data class AlreadyCommitted(
        override val file: File,
        override val currentSourceHash: String
    ) : MemoryFileCommitOutcome

    data class Conflict(
        override val file: File,
        override val currentSourceHash: String,
        val expectedBaseSourceHash: String,
        val targetSourceHash: String
    ) : MemoryFileCommitOutcome

    data class UnrecoverableStaging(
        override val file: File,
        override val currentSourceHash: String,
        val reason: String
    ) : MemoryFileCommitOutcome
}

const val MEMORY_MUTATION_UNRECOVERABLE_STAGING_PREFIX = "memory_mutation_unrecoverable_staging_"
const val MEMORY_MUTATION_UNRECOVERABLE_STAGING_MISSING = "${MEMORY_MUTATION_UNRECOVERABLE_STAGING_PREFIX}missing"
const val MEMORY_MUTATION_UNRECOVERABLE_STAGING_INVALID = "${MEMORY_MUTATION_UNRECOVERABLE_STAGING_PREFIX}invalid"
const val MEMORY_MUTATION_UNRECOVERABLE_STAGING_HASH_MISMATCH =
    "${MEMORY_MUTATION_UNRECOVERABLE_STAGING_PREFIX}hash_mismatch"

internal data class MemoryFileCorpusRead(
    val revision: Long,
    val files: List<MemoryFileContent>
)

internal data class MemoryFileContent(
    val sourcePath: String,
    val bytes: ByteArray
)
