package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 1: the budget model is the single source of truth for "how many tokens may this request use".
 *
 * The invariants are asserted for every provider fallback and for a wide sweep of window sizes,
 * because a budget that violates them does not fail loudly at runtime — it quietly makes
 * RAW_PREFERRED compact on turn one, or makes compaction never trigger at all.
 */
class ContextBudgetTest {

    @Test
    fun `invariants hold for every client type fallback window`() {
        ClientType.entries.forEach { clientType ->
            val policy = ProviderContextPolicy.forClientType(clientType)
            val budget = ContextBudget.of(policy.fallbackModelContextWindow, policy)

            assertInvariants(clientType.name, budget)
        }
    }

    @Test
    fun `invariants hold across a sweep of window sizes`() {
        val windows = listOf(
            1, 100, 1_000, 2_047, 2_048, 2_049, 3_000, 4_096, 6_000, 8_192,
            16_385, 32_768, 65_536, 128_000, 200_000, 400_000, 1_000_000, 1_047_576
        )
        val summaryTargets = listOf(0, 700, 800, 1_200, 2_000, 5_000)

        windows.forEach { window ->
            summaryTargets.forEach { summaryTarget ->
                val budget = ContextBudget.of(window, summaryTarget)
                assertInvariants("window=$window summaryTarget=$summaryTarget", budget)
            }
        }
    }

    @Test
    fun `budget is never built from the legacy construction budget`() {
        // The whole point of Task 0/1: maxContextTokens is a local construction budget for the
        // legacy extractive path, not a model window. Feeding it in would shrink the real budget
        // ~5x on OpenAI and make RAW_PREFERRED compact immediately.
        val policy = ProviderContextPolicy.forClientType(ClientType.OPENAI)
        val correct = ContextBudget.of(policy.fallbackModelContextWindow, policy)
        val wrong = ContextBudget.of(policy.maxContextTokens, policy)

        assertTrue(
            "the real window must yield a much larger budget than the construction budget",
            correct.effectiveInputBudget > wrong.effectiveInputBudget * 4
        )
    }

    @Test
    fun `output reserve and safety margin are capped in absolute terms`() {
        val budget = ContextBudget.of(1_000_000, 1_200)

        // 15% of 1M would be 150k, which is far more output than any of these models can emit.
        assertEquals(8_192, budget.outputReserve)
        assertEquals(2_048, budget.safetyMargin)
    }

    @Test
    fun `output reserve and safety margin are proportional on small windows`() {
        val budget = ContextBudget.of(8_192, 1_200)

        assertEquals((8_192 * 0.15).toInt(), budget.outputReserve)
        assertEquals((8_192 * 0.05).toInt(), budget.safetyMargin)
    }

    @Test
    fun `compact trigger is capped by the cost ceiling on large windows`() {
        val budget = ContextBudget.of(1_000_000, 1_200)

        assertEquals(ContextBudget.COMPACT_TRIGGER_CEILING, budget.compactTrigger)
        assertTrue(
            "the ceiling must actually bind here, otherwise this test proves nothing",
            budget.effectiveInputBudget * 0.75 > ContextBudget.COMPACT_TRIGGER_CEILING
        )
    }

    @Test
    fun `pre fold trigger keeps a usable gap even when the cost ceiling binds`() {
        // Clamping preFoldTrigger to compactTrigger - 1 would satisfy the invariant while leaving
        // the background job no time to run, which defeats the purpose of having two lines.
        val budget = ContextBudget.of(1_000_000, 1_200)
        val gap = budget.compactTrigger - budget.preFoldTrigger

        assertTrue("gap was only $gap tokens", gap >= budget.compactTrigger / 10)
    }

    @Test
    fun `pre fold trigger is proportional on windows where the ceiling does not bind`() {
        val budget = ContextBudget.of(32_768, 1_200)

        assertEquals((budget.effectiveInputBudget * 0.60).toInt(), budget.preFoldTrigger)
        assertEquals((budget.effectiveInputBudget * 0.75).toInt(), budget.compactTrigger)
    }

    @Test
    fun `tiny window degrades to the defensive floor instead of a negative budget`() {
        val budget = ContextBudget.of(1, 1_200)

        assertEquals(ContextBudget.MIN_INPUT_BUDGET, budget.modelContextWindow)
        assertEquals(ContextBudget.MIN_INPUT_BUDGET, budget.effectiveInputBudget)
        assertInvariants("tiny", budget)
    }

    @Test
    fun `summary target is clamped into the plan range`() {
        // A provider asking for 100 gets the 768 floor; one asking for 5000 gets the 2048 ceiling.
        assertEquals(768, ContextBudget.of(128_000, 100).summaryTarget)
        assertEquals(2_048, ContextBudget.of(128_000, 5_000).summaryTarget)
        assertEquals(1_200, ContextBudget.of(128_000, 1_200).summaryTarget)
    }

    @Test
    fun `recent raw tail is capped in absolute terms`() {
        assertEquals(16_000, ContextBudget.of(1_000_000, 1_200).recentRawTail)
        assertEquals(16_000, ContextBudget.of(200_000, 1_200).recentRawTail)
    }

    @Test
    fun `raw warning threshold sits below the effective budget`() {
        val budget = ContextBudget.of(128_000, 1_200)

        assertTrue(budget.rawWarningThreshold < budget.effectiveInputBudget)
        assertEquals((budget.effectiveInputBudget * 0.80).toInt(), budget.rawWarningThreshold)
    }

    @Test
    fun `trigger predicates fire at their thresholds`() {
        val budget = ContextBudget.of(128_000, 1_200)

        assertFalse(budget.shouldPreFold(budget.preFoldTrigger - 1))
        assertTrue(budget.shouldPreFold(budget.preFoldTrigger))

        assertFalse(budget.shouldCompactNow(budget.compactTrigger - 1))
        assertTrue(budget.shouldCompactNow(budget.compactTrigger))

        assertFalse(budget.exceedsBudget(budget.effectiveInputBudget))
        assertTrue(budget.exceedsBudget(budget.effectiveInputBudget + 1))
    }

    @Test
    fun `pre folding starts before synchronous compaction becomes necessary`() {
        // The whole design depends on this ordering: the background job gets to run in the gap, and
        // compactTrigger degrades into a rarely-reached fallback.
        val budget = ContextBudget.of(128_000, 1_200)

        assertTrue(budget.shouldPreFold(budget.preFoldTrigger))
        assertFalse(budget.shouldCompactNow(budget.preFoldTrigger))
    }

    private fun assertInvariants(label: String, budget: ContextBudget) {
        assertTrue("$label: recentRawTail must be positive", budget.recentRawTail > 0)
        assertTrue(
            "$label: recentRawTail(${budget.recentRawTail}) + summaryTarget(${budget.summaryTarget}) " +
                "must be < compactTrigger(${budget.compactTrigger})",
            budget.recentRawTail + budget.summaryTarget < budget.compactTrigger
        )
        assertTrue(
            "$label: preFoldTrigger(${budget.preFoldTrigger}) must be < compactTrigger(${budget.compactTrigger})",
            budget.preFoldTrigger < budget.compactTrigger
        )
        assertTrue(
            "$label: compactTrigger(${budget.compactTrigger}) must be <= " +
                "effectiveInputBudget(${budget.effectiveInputBudget})",
            budget.compactTrigger <= budget.effectiveInputBudget
        )
        assertTrue(
            "$label: effectiveInputBudget(${budget.effectiveInputBudget}) must be <= " +
                "modelContextWindow(${budget.modelContextWindow})",
            budget.effectiveInputBudget <= budget.modelContextWindow
        )
    }
}
