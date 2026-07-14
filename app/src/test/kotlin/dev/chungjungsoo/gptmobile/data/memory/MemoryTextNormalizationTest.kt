package dev.chungjungsoo.gptmobile.data.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryTextNormalizationTest {

    @Test
    fun `normalization collapses Unicode whitespace and preserves code points`() {
        assertEquals(
            "mixed whitespace \ud83d\ude00 preserved",
            normalizeExactMemoryText("\u00a0MiXeD\u0085\u2003whitespace\n\ud83d\ude00\u202fpreserved\u3000")
        )
    }
}
