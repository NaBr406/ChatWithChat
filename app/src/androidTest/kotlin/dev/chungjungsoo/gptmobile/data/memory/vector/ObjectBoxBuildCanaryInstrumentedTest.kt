package dev.chungjungsoo.gptmobile.data.memory.vector

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.objectbox.BoxStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ObjectBoxBuildCanaryInstrumentedTest {
    @Test
    fun hnswStore_opensQueriesAndReopensBelowNoBackupFilesDir() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "memory_vector_canary")
        BoxStore.deleteAllFiles(directory)

        try {
            val queryEmbedding = FloatArray(MEMORY_VECTOR_DIMENSION).apply { this[0] = 1f }
            MyObjectBox.builder()
                .androidContext(context)
                .directory(directory)
                .build()
                .use { store ->
                    store.boxFor(MemoryVectorChunkEntity::class.java).put(
                        MemoryVectorChunkEntity(
                            chunkId = "canary-target",
                            sourcePath = "MEMORY.md",
                            text = "ObjectBox build canary",
                            embedding = queryEmbedding
                        )
                    )
                    assertNearestChunk(store, queryEmbedding, "canary-target")
                }

            assertTrue(directory.startsWith(context.noBackupFilesDir))
            MyObjectBox.builder()
                .androidContext(context)
                .directory(directory)
                .build()
                .use { reopenedStore ->
                    assertNearestChunk(reopenedStore, queryEmbedding, "canary-target")
                }
        } finally {
            BoxStore.deleteAllFiles(directory)
        }
    }

    private fun assertNearestChunk(
        store: BoxStore,
        embedding: FloatArray,
        expectedChunkId: String
    ) {
        val box = store.boxFor(MemoryVectorChunkEntity::class.java)
        box.query(MemoryVectorChunkEntity_.embedding.nearestNeighbors(embedding, 1))
            .build()
            .use { query ->
                val nearest = query.findWithScores().single()
                assertEquals(expectedChunkId, nearest.get().chunkId)
            }
    }
}
