package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

interface MemoryMaintenanceFailureResponseStore {
    fun record(job: MemoryMaintenanceJob, failureReason: String, rawResponseJson: String)

    fun pruneExpired()

    data object None : MemoryMaintenanceFailureResponseStore {
        override fun record(job: MemoryMaintenanceJob, failureReason: String, rawResponseJson: String) = Unit

        override fun pruneExpired() = Unit
    }
}

/**
 * Keeps exact model payloads out of the canonical memory corpus while retaining failed maintenance
 * executions long enough for diagnosis. The filename carries only operational metadata; file bytes
 * are the response exactly as returned by the model.
 */
class FileMemoryMaintenanceFailureResponseStore(
    private val paths: MemoryFilePaths,
    private val clock: Clock = Clock.systemDefaultZone()
) : MemoryMaintenanceFailureResponseStore {
    override fun record(job: MemoryMaintenanceJob, failureReason: String, rawResponseJson: String) {
        if (rawResponseJson.isBlank()) return
        runCatching {
            val directory = paths.maintenanceFailureResponseDirectory
            directory.mkdirs()
            require(directory.isDirectory) { "Unable to create maintenance failure response directory" }
            pruneExpired()
            val capturedAt = now()
            val filename = buildString {
                append(FILE_NAME_PREFIX)
                append(capturedAt)
                append('-')
                append(sha256(job.jobId).take(FILE_JOB_HASH_LENGTH))
                append("-attempt-")
                append(job.attempts)
                append('-')
                append(sha256("$failureReason|$rawResponseJson").take(FILE_CONTENT_HASH_LENGTH))
                append('-')
                append(UUID.randomUUID())
                append(RESPONSE_FILE_SUFFIX)
            }
            writeAtomically(File(directory, filename), rawResponseJson)
        }
    }

    override fun pruneExpired() {
        runCatching {
            val cutoff = now() - RETENTION_SECONDS
            paths.maintenanceFailureResponseDirectory.listFiles()
                .orEmpty()
                .filter { file ->
                    file.isFile &&
                        file.name.startsWith(FILE_NAME_PREFIX) &&
                        file.name.endsWith(RESPONSE_FILE_SUFFIX) &&
                        fileTimestamp(file) < cutoff
                }
                .forEach(File::delete)
        }
    }

    private fun fileTimestamp(file: File): Long = file.name
        .removePrefix(FILE_NAME_PREFIX)
        .substringBefore('-', missingDelimiterValue = "")
        .toLongOrNull()
        ?: Long.MAX_VALUE

    private fun writeAtomically(target: File, content: String) {
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            temporary.writeText(content, StandardCharsets.UTF_8)
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (atomicMoveFailure: Exception) {
            if (temporary.exists()) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                throw atomicMoveFailure
            }
        } finally {
            temporary.delete()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun now(): Long = clock.instant().epochSecond

    private companion object {
        const val RETENTION_SECONDS = 7 * 24 * 60 * 60L
        const val FILE_JOB_HASH_LENGTH = 16
        const val FILE_CONTENT_HASH_LENGTH = 16
        const val FILE_NAME_PREFIX = "maintenance-failure-"
        const val RESPONSE_FILE_SUFFIX = ".json"
    }
}
