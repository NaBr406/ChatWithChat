package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The strategy enum is the one piece of this feature a user picks by hand, so its storage round-trip
 * has to survive everything a settings column can hold: a column added by a migration with no value
 * yet, a value written by an older build, a value someone typed into a backup file.
 *
 * Falling back to [ContextStrategy.DEFAULT] rather than throwing is the whole point — an unreadable
 * setting must degrade to "manage the context automatically", never to a crash on chat open.
 */
class ContextStrategyTest {

    @Test
    fun `default strategy is auto compact`() {
        assertEquals(ContextStrategy.AUTO_COMPACT_BALANCED, ContextStrategy.DEFAULT)
    }

    @Test
    fun `stored strategy round trips through its own name`() {
        ContextStrategy.entries.forEach { strategy ->
            assertEquals(strategy, ContextStrategy.fromStorage(strategy.name))
        }
    }

    @Test
    fun `null column value falls back to the default`() {
        assertEquals(ContextStrategy.DEFAULT, ContextStrategy.fromStorage(null))
    }

    @Test
    fun `blank and whitespace column values fall back to the default`() {
        assertEquals(ContextStrategy.DEFAULT, ContextStrategy.fromStorage(""))
        assertEquals(ContextStrategy.DEFAULT, ContextStrategy.fromStorage("   "))
        assertEquals(ContextStrategy.DEFAULT, ContextStrategy.fromStorage("\n\t"))
    }

    @Test
    fun `case and surrounding whitespace are tolerated`() {
        assertEquals(ContextStrategy.RAW_PREFERRED, ContextStrategy.fromStorage("raw_preferred"))
        assertEquals(ContextStrategy.RAW_PREFERRED, ContextStrategy.fromStorage("  RAW_PREFERRED  "))
        assertEquals(ContextStrategy.RAW_PREFERRED, ContextStrategy.fromStorage("Raw_Preferred"))
        assertEquals(ContextStrategy.AUTO_COMPACT_BALANCED, ContextStrategy.fromStorage(" auto_compact\n"))
    }

    @Test
    fun `unknown legacy value falls back to the default instead of throwing`() {
        assertEquals(ContextStrategy.DEFAULT, ContextStrategy.fromStorage("FULL_HISTORY"))
        assertEquals(ContextStrategy.DEFAULT, ContextStrategy.fromStorage("recent-n"))
        assertEquals(ContextStrategy.DEFAULT, ContextStrategy.fromStorage("0"))
    }

    @Test
    fun `new platform rows still carry the legacy column default`() {
        val platform = PlatformV2(
            name = "Fresh",
            compatibleType = ClientType.CUSTOM,
            apiUrl = "https://example.com",
            model = "custom-model"
        )

        // Deliberately the legacy string, not AUTO_COMPACT_BALANCED.name: `platform_v2` declares
        // `DEFAULT 'AUTO_COMPACT'` and that literal is part of schema 21's identity hash. Adding tiers
        // must not become a schema-22 migration, so the aliasing lives in fromStorage instead. If this
        // assertion is ever "fixed" to the enum name, the column default and the enum have diverged.
        assertEquals("AUTO_COMPACT", platform.contextStrategy)
        assertEquals(ContextStrategy.AUTO_COMPACT_BALANCED, ContextStrategy.fromStorage(platform.contextStrategy))
    }

    @Test
    fun `legacy auto compact value maps onto the balanced tier`() {
        assertEquals(ContextStrategy.AUTO_COMPACT_BALANCED, ContextStrategy.fromStorage("AUTO_COMPACT"))
        assertEquals(ContextStrategy.AUTO_COMPACT_BALANCED, ContextStrategy.fromStorage("auto_compact"))
        assertEquals(ContextStrategy.AUTO_COMPACT_BALANCED, ContextStrategy.fromStorage(" Auto_Compact \n"))
    }

    @Test
    fun `balanced tier reproduces the behaviour the legacy value had`() {
        // The upgrade contract: a provider row written before tiers existed must not change behaviour
        // just because the enum grew. BALANCED holds the previously hard-coded ceilings.
        val legacy = ContextStrategy.fromStorage("AUTO_COMPACT")

        assertEquals(ContextBudgetTier.BALANCED, legacy.budgetTier)
        assertEquals(ContextBudgetTier.DEFAULT, legacy.budgetTier)
        assertFalse(legacy.sendsRawWhileCapacityAllows)
    }

    @Test
    fun `every auto compact value organises history the same way and differs only by tier`() {
        val autoCompact = ContextStrategy.entries.filter { it.isAutoCompact }

        assertEquals(4, autoCompact.size)
        autoCompact.forEach { strategy ->
            assertFalse(strategy.name, strategy.sendsRawWhileCapacityAllows)
        }
        // Four distinct tiers, so no two entries in the picker do the same thing.
        assertEquals(autoCompact.size, autoCompact.map { it.budgetTier }.distinct().size)
    }

    @Test
    fun `raw preferred is the only value that hands the whole budget to verbatim turns`() {
        val rawStrategies = ContextStrategy.entries.filter { it.sendsRawWhileCapacityAllows }

        assertEquals(listOf(ContextStrategy.RAW_PREFERRED), rawStrategies)
    }

    @Test
    fun `strategy is stored per provider so one provider never overrides another`() {
        val rawProvider = PlatformV2(
            uid = "raw-provider",
            name = "Raw",
            compatibleType = ClientType.CUSTOM,
            apiUrl = "https://example.com",
            model = "custom-model",
            contextStrategy = ContextStrategy.RAW_PREFERRED.name
        )
        val autoProvider = PlatformV2(
            uid = "auto-provider",
            name = "Auto",
            compatibleType = ClientType.CUSTOM,
            apiUrl = "https://example.com",
            model = "custom-model"
        )

        assertEquals(ContextStrategy.RAW_PREFERRED, ContextStrategy.fromStorage(rawProvider.contextStrategy))
        assertEquals(ContextStrategy.AUTO_COMPACT_BALANCED, ContextStrategy.fromStorage(autoProvider.contextStrategy))
        assertNotEquals(rawProvider.contextStrategy, autoProvider.contextStrategy)
    }
}
