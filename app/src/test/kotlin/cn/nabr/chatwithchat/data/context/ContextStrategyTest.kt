package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ClientType
import org.junit.Assert.assertEquals
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
        assertEquals(ContextStrategy.AUTO_COMPACT, ContextStrategy.DEFAULT)
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
        assertEquals(ContextStrategy.AUTO_COMPACT, ContextStrategy.fromStorage(" auto_compact\n"))
    }

    @Test
    fun `unknown legacy value falls back to the default instead of throwing`() {
        assertEquals(ContextStrategy.DEFAULT, ContextStrategy.fromStorage("FULL_HISTORY"))
        assertEquals(ContextStrategy.DEFAULT, ContextStrategy.fromStorage("recent-n"))
        assertEquals(ContextStrategy.DEFAULT, ContextStrategy.fromStorage("0"))
    }

    @Test
    fun `new platform rows default to auto compact`() {
        val platform = PlatformV2(
            name = "Fresh",
            compatibleType = ClientType.CUSTOM,
            apiUrl = "https://example.com",
            model = "custom-model"
        )

        assertEquals(ContextStrategy.AUTO_COMPACT.name, platform.contextStrategy)
        assertEquals(ContextStrategy.AUTO_COMPACT, ContextStrategy.fromStorage(platform.contextStrategy))
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
        assertEquals(ContextStrategy.AUTO_COMPACT, ContextStrategy.fromStorage(autoProvider.contextStrategy))
        assertNotEquals(rawProvider.contextStrategy, autoProvider.contextStrategy)
    }
}
