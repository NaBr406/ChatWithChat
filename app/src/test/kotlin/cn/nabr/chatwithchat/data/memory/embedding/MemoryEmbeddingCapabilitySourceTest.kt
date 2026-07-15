package cn.nabr.chatwithchat.data.memory.embedding

import cn.nabr.chatwithchat.data.memory.vector.MemoryVectorIndexDefaults
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEmbeddingCapabilitySourceTest {
    @Test
    fun `source starts not provisioned and publishes ready after validation`() {
        val source = MutableMemoryEmbeddingCapabilitySource()

        val unavailable = source.current() as MemoryEmbeddingCapability.Unavailable
        assertEquals(
            MemoryEmbeddingAvailability.Reason.NOT_PROVISIONED,
            (unavailable.availability as MemoryEmbeddingAvailability.Unavailable).reason
        )

        val configuration = MemoryVectorIndexDefaults.configuration
        val provider = DeterministicFakeMemoryEmbeddingProvider(configuration.embeddingDescriptor)
        val ready = source.setReady(provider, configuration)

        assertSame(ready, source.current())
        assertSame(provider, ready.provider)
    }

    @Test
    fun `source publishes capability transitions safely across threads`() {
        val source = MutableMemoryEmbeddingCapabilitySource()
        val configuration = MemoryVectorIndexDefaults.configuration
        val provider = DeterministicFakeMemoryEmbeddingProvider(configuration.embeddingDescriptor)
        val executor = Executors.newFixedThreadPool(4)
        val started = CountDownLatch(1)
        val finished = CountDownLatch(4)

        repeat(4) { worker ->
            executor.execute {
                started.await()
                repeat(1_000) { iteration ->
                    if ((worker + iteration) % 2 == 0) {
                        source.setReady(provider, configuration)
                    } else {
                        source.setUnavailable(
                            MemoryEmbeddingAvailability.Unavailable(
                                MemoryEmbeddingAvailability.Reason.NOT_PROVISIONED
                            )
                        )
                    }
                    when (source.current()) {
                        is MemoryEmbeddingCapability.Ready -> Unit
                        is MemoryEmbeddingCapability.Unavailable -> Unit
                    }
                }
                finished.countDown()
            }
        }

        started.countDown()
        assertTrue(finished.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()
        when (source.current()) {
            is MemoryEmbeddingCapability.Ready -> Unit
            is MemoryEmbeddingCapability.Unavailable -> Unit
        }
    }
}
