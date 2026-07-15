package cn.nabr.chatwithchat.data.memory.vector

import android.content.Context
import java.io.File

class MemoryVectorStoreFactory(
    context: Context
) {
    private val applicationContext = context.applicationContext

    fun create(): MemoryVectorStore = ObjectBoxMemoryVectorStore(
        context = applicationContext,
        directory = MemoryVectorStoreDirectory.production(applicationContext)
    )

    internal fun createForTesting(
        directory: File,
        beforeManifestPublished: () -> Unit = {}
    ): MemoryVectorStore = ObjectBoxMemoryVectorStore(
        context = applicationContext,
        directory = MemoryVectorStoreDirectory.testing(applicationContext, directory),
        beforeManifestPublished = beforeManifestPublished
    )
}
