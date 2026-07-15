package cn.nabr.chatwithchat.data.memory.vector

import android.content.Context
import io.objectbox.BoxStore
import java.io.File
import java.io.IOException

const val MEMORY_VECTOR_STORE_ROOT_DIRECTORY = "memory_vector_index"
const val MEMORY_VECTOR_STORE_VERSION_DIRECTORY = "v1-d512"

internal class MemoryVectorStoreDirectory private constructor(
    private val noBackupFilesDirectory: File,
    val file: File
) {
    init {
        requireContainedDirectory()
    }

    fun deleteAll() {
        requireContainedDirectory()
        if (!file.exists()) return

        BoxStore.deleteAllFiles(file)
        if (file.exists() && !file.deleteRecursively()) {
            throw IOException("Unable to delete the derived memory vector store")
        }
    }

    private fun requireContainedDirectory() {
        val rootPath = noBackupFilesDirectory.canonicalFile.toPath()
        val storePath = file.canonicalFile.toPath()
        require(storePath != rootPath && storePath.startsWith(rootPath)) {
            "Memory vector store must be a child of noBackupFilesDir"
        }
    }

    companion object {
        fun production(context: Context): MemoryVectorStoreDirectory {
            val root = context.noBackupFilesDir
            return MemoryVectorStoreDirectory(
                noBackupFilesDirectory = root,
                file = File(root, "$MEMORY_VECTOR_STORE_ROOT_DIRECTORY/$MEMORY_VECTOR_STORE_VERSION_DIRECTORY")
            )
        }

        internal fun testing(
            context: Context,
            directory: File
        ): MemoryVectorStoreDirectory = MemoryVectorStoreDirectory(
            noBackupFilesDirectory = context.noBackupFilesDir,
            file = directory
        )
    }
}
