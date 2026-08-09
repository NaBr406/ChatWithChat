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
    fun `invariants hold for every client type fallback window on every tier`() {
        ClientType.entries.forEach { clientType ->
            val policy = ProviderContextPolicy.forClientType(clientType)
            ContextBudgetTier.entries.forEach { tier ->
                val budget = ContextBudget.of(policy.fallbackModelContextWindow, policy, tier)

                assertInvariants("${clientType.name}/${tier.name}", budget)
                assertEquals(tier, budget.tier)
            }
        }
    }

    @Test
    fun `invariants hold across a sweep of window sizes on every tier`() {
        // The tier dimension matters most at the small end: a high summaryScale on a tiny window is
        // exactly what pushes `recentRawTail + summaryTarget` past compactTrigger, and the factory has
        // to squeeze the summary rather than emit a budget that fails check().
        val windows = listOf(
            1, 100, 1_000, 2_047, 2_048, 2_049, 3_000, 4_096, 6_000, 8_192,
            16_385, 32_768, 65_536, 128_000, 200_000, 400_000, 1_000_000, 1_047_576
        )
        val summaryTargets = listOf(0, 700, 800, 1_200, 2_000, 5_000)

        windows.forEach { window ->
            summaryTargets.forEach { summaryTarget ->
                ContextBudgetTier.entries.forEach { tier ->
                    val budget = ContextBudget.of(window, summaryTarget, tier)
                    assertInvariants("window=$window summaryTarget=$summaryTarget tier=${tier.name}", budget)
                }
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
        // The ceiling moved from a constant on ContextBudget onto the tier, so that the cost/fidelity
        // trade-off is the user's to make. The default tier must still cap exactly where it used to.
        val ceiling = requireNotNull(ContextBudgetTier.BALANCED.compactTriggerCeiling)
        val budget = ContextBudget.of(1_000_000, 1_200)

        assertEquals(ceiling, budget.compactTrigger)
        assertTrue(
            "the ceiling must actually bind here, otherwise this test proves nothing",
            budget.effectiveInputBudget * 0.75 > ceiling
        )
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
    fun `omitting the tier reproduces the balanced ceilings exactly`() {
        // Existing callers that never pass a tier must be bit-for-bit unchanged, otherwise adding the
        // setting would silently alter every provider a user already configured.
        listOf(8_192, 32_768, 128_000, 1_000_000).forEach { window ->
            val implicit = ContextBudget.of(window, 1_200)
            val explicit = ContextBudget.of(window, 1_200, ContextBudgetTier.BALANCED)

            assertEquals("window=$window", explicit, implicit)
        }
        assertEquals(ContextBudgetTier.BALANCED, ContextBudgetTier.DEFAULT)
    }

    @Test
    fun `each tier caps a large window at its own ceilings`() {
        // 1M is the case that motivated the setting: under BALANCED a million-token model was being
        // driven like a 128k one, and the user had no way to say otherwise.
        assertEquals(32_000, ContextBudget.of(1_000_000, 1_200, ContextBudgetTier.ECONOMY).compactTrigger)
        assertEquals(96_000, ContextBudget.of(1_000_000, 1_200, ContextBudgetTier.BALANCED).compactTrigger)
        assertEquals(200_000, ContextBudget.of(1_000_000, 1_200, ContextBudgetTier.GENEROUS).compactTrigger)

        assertEquals(8_000, ContextBudget.of(1_000_000, 1_200, ContextBudgetTier.ECONOMY).recentRawTail)
        assertEquals(16_000, ContextBudget.of(1_000_000, 1_200, ContextBudgetTier.BALANCED).recentRawTail)
        assertEquals(48_000, ContextBudget.of(1_000_000, 1_200, ContextBudgetTier.GENEROUS).recentRawTail)
    }

    @Test
    fun `follow window tier applies the ratios to the real window with no ceiling at all`() {
        val budget = ContextBudget.of(1_000_000, 1_200, ContextBudgetTier.FOLLOW_WINDOW)

        assertTrue(ContextBudgetTier.FOLLOW_WINDOW.followsWindow)
        // Expressed as the same expression the factory uses rather than a literal: 0.30 and 0.60 are
        // not exactly representable, so a hand-computed literal can be one token off after truncation.
        assertEquals((budget.effectiveInputBudget * 0.75).toInt(), budget.compactTrigger)
        assertEquals((budget.effectiveInputBudget * 0.60).toInt(), budget.preFoldTrigger)
        assertEquals((budget.effectiveInputBudget * 0.30).toInt(), budget.recentRawTail)
        assertTrue(
            "a 1M model should keep far more than the balanced tier's 16k tail",
            budget.recentRawTail > 250_000
        )
    }

    @Test
    fun `a small window ignores the tier because no ceiling can bind`() {
        // The tier lowers ceilings; it can never raise a budget above what the window supports. On an
        // 8k model every tier must therefore agree, so switching tiers cannot break a small provider.
        val budgets = ContextBudgetTier.entries.map { ContextBudget.of(8_192, 1_200, it) }

        assertEquals(1, budgets.map { it.compactTrigger }.distinct().size)
        assertEquals(1, budgets.map { it.recentRawTail }.distinct().size)
        assertEquals(1, budgets.map { it.effectiveInputBudget }.distinct().size)
    }

    @Test
    fun `summary target scales with the tier where a ceiling alone would have done nothing`() {
        // Providers only ever ask for 700..1_200 tokens of summary, so a tier that merely raised a
        // ceiling would be a no-op. Scaling first is what makes a higher tier buy a richer checkpoint.
        val window = 1_000_000

        assertEquals(900, ContextBudget.of(window, 1_200, ContextBudgetTier.ECONOMY).summaryTarget)
        assertEquals(1_200, ContextBudget.of(window, 1_200, ContextBudgetTier.BALANCED).summaryTarget)
        assertEquals(2_400, ContextBudget.of(window, 1_200, ContextBudgetTier.GENEROUS).summaryTarget)
        assertEquals(4_800, ContextBudget.of(window, 1_200, ContextBudgetTier.FOLLOW_WINDOW).summaryTarget)
    }

    @Test
    fun `the summary floor still wins over an aggressive downward scale`() {
        // ECONOMY scales down, but a checkpoint below the floor is not worth storing at all.
        val budget = ContextBudget.of(128_000, 700, ContextBudgetTier.ECONOMY)

        assertEquals(768, budget.summaryTarget)
    }

    @Test
    fun `raising the tier never lowers the budget on any window`() {
        // The user-facing promise of the picker: every step up gives at least as much context as the
        // step below. Both quantities come from min(ratio, ceiling) with strictly increasing ceilings,
        // so this must hold for every window, not just the ones with round numbers.
        val windows = listOf(2_048, 8_192, 32_768, 65_536, 128_000, 200_000, 400_000, 1_000_000)
        val ladder = listOf(
            ContextBudgetTier.ECONOMY,
            ContextBudgetTier.BALANCED,
            ContextBudgetTier.GENEROUS,
            ContextBudgetTier.FOLLOW_WINDOW
        )

        windows.forEach { window ->
            ladder.zipWithNext { lower, higher ->
                val lowerBudget = ContextBudget.of(window, 1_200, lower)
                val higherBudget = ContextBudget.of(window, 1_200, higher)
                val label = "window=$window ${lower.name} -> ${higher.name}"

                assertTrue(
                    "$label: compactTrigger went backwards",
                    higherBudget.compactTrigger >= lowerBudget.compactTrigger
                )
                assertTrue(
                    "$label: recentRawTail went backwards",
                    higherBudget.recentRawTail >= lowerBudget.recentRawTail
                )
            }
        }
    }

    @Test
    fun `every tier still leaves the background job a usable gap on a large window`() {
        // Clamping preFoldTrigger to compactTrigger - 1 would satisfy check() while giving the async
        // fold no time to run. The proportion has to survive on the tiers whose ceiling binds too.
        ContextBudgetTier.entries.forEach { tier ->
            val budget = ContextBudget.of(1_000_000, 1_200, tier)
            val gap = budget.compactTrigger - budget.preFoldTrigger

            assertTrue("${tier.name}: gap was only $gap tokens", gap >= budget.compactTrigger / 10)
        }
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
