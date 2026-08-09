package cn.nabr.chatwithchat.data.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tier table is plain data, which is exactly why it needs guarding: nothing in the compiler stops
 * someone reordering the entries or nudging a ceiling, and either would silently change what every
 * provider already configured is spending per request.
 *
 * [ContextBudgetTest] covers what the tiers *do* to a budget. This covers the table's own shape.
 */
class ContextBudgetTierTest {

    @Test
    fun `balanced holds the constants that used to be hard coded`() {
        // These three literals were `COMPACT_TRIGGER_CEILING`, `RECENT_RAW_TAIL_CEILING` and
        // `SUMMARY_TARGET_MAX` on ContextBudget. Because BALANCED is also the default and the legacy
        // 'AUTO_COMPACT' column value aliases onto it, changing any of them changes behaviour for
        // every provider that has never touched the setting.
        assertEquals(96_000, ContextBudgetTier.BALANCED.compactTriggerCeiling)
        assertEquals(16_000, ContextBudgetTier.BALANCED.recentRawTailCeiling)
        assertEquals(2_048, ContextBudgetTier.BALANCED.summaryTargetCeiling)
        assertEquals(1.0, ContextBudgetTier.BALANCED.summaryScale, 0.0)
        assertEquals(ContextBudgetTier.BALANCED, ContextBudgetTier.DEFAULT)
    }

    @Test
    fun `declaration order is the ladder order the picker renders`() {
        // The settings UI iterates `entries`, so declaration order is user-visible: it must read as
        // cheapest to most expensive rather than as whatever order the entries were typed in.
        assertEquals(
            listOf(
                ContextBudgetTier.ECONOMY,
                ContextBudgetTier.BALANCED,
                ContextBudgetTier.GENEROUS,
                ContextBudgetTier.FOLLOW_WINDOW
            ),
            ContextBudgetTier.entries.toList()
        )
    }

    @Test
    fun `follow window is the only tier without ceilings`() {
        assertNull(ContextBudgetTier.FOLLOW_WINDOW.compactTriggerCeiling)
        assertNull(ContextBudgetTier.FOLLOW_WINDOW.recentRawTailCeiling)
        assertTrue(ContextBudgetTier.FOLLOW_WINDOW.followsWindow)

        ContextBudgetTier.entries
            .filter { it != ContextBudgetTier.FOLLOW_WINDOW }
            .forEach { tier ->
                assertFalse(tier.name, tier.followsWindow)
                assertTrue(tier.name, tier.compactTriggerCeiling != null)
                assertTrue(tier.name, tier.recentRawTailCeiling != null)
            }
    }

    @Test
    fun `every ceiling grows strictly with the tier`() {
        // ContextBudget derives both quantities as min(ratio, ceiling). Strictly increasing ceilings
        // are what make "one step up is never less context" true for every window size.
        ContextBudgetTier.entries.zipWithNext { lower, higher ->
            val label = "${lower.name} -> ${higher.name}"

            assertTrue(label, ceilingOf(higher.compactTriggerCeiling) > ceilingOf(lower.compactTriggerCeiling))
            assertTrue(label, ceilingOf(higher.recentRawTailCeiling) > ceilingOf(lower.recentRawTailCeiling))
            assertTrue(label, higher.summaryScale > lower.summaryScale)
            assertTrue(label, higher.summaryTargetCeiling > lower.summaryTargetCeiling)
        }
    }

    @Test
    fun `the raw tail ceiling always leaves room under the compaction ceiling`() {
        // If these ever crossed, the tail alone would exceed the compaction line and ContextBudget
        // would have to shrink it back, making the tier mean something other than what it says.
        ContextBudgetTier.entries.forEach { tier ->
            assertTrue(
                tier.name,
                ceilingOf(tier.recentRawTailCeiling) < ceilingOf(tier.compactTriggerCeiling)
            )
        }
    }

    @Test
    fun `no tier ceiling sits below the summary floor`() {
        // 768 is ContextBudget's SUMMARY_TARGET_MIN (private there, so restated here). A ceiling below
        // it would make the coerceIn range invalid and throw on every budget built with that tier.
        ContextBudgetTier.entries.forEach { tier ->
            assertTrue(
                "${tier.name} ceiling ${tier.summaryTargetCeiling} is below the 768 floor",
                tier.summaryTargetCeiling >= 768
            )
            assertTrue("${tier.name} scale must be positive", tier.summaryScale > 0.0)
        }
    }

    private fun ceilingOf(ceiling: Int?): Int = ceiling ?: Int.MAX_VALUE
}
