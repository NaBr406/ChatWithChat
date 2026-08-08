package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.model.ClientType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 1: the native-compaction capability gate.
 *
 * The gate is a safety net for free-text model names, not a whitelist meant to exclude mainstream
 * models. The two properties that matter are: never send `context_management` to a non-official
 * client type, and never fail hard on an unrecognised name — degrade to the app-side path instead.
 */
class NativeCompactionModelGateTest {

    @Test
    fun `current openai families are supported`() {
        listOf("gpt-5.4", "gpt-5.4-mini", "gpt-5.3", "gpt-5.2", "gpt-5.2-pro").forEach { model ->
            assertTrue(
                "$model should be gated on",
                NativeCompactionModelGate.supportsServerSideCompaction(ClientType.OPENAI, model)
            )
        }
    }

    @Test
    fun `families predating server side compaction are gated off`() {
        // Compaction was introduced by GPT-5.2. A bare "gpt-5" prefix in the table would silently
        // admit gpt-5 and gpt-5.1, which would fail on every request.
        listOf("gpt-5", "gpt-5-pro", "gpt-5.1", "gpt-5.1-mini").forEach { model ->
            assertFalse(
                "$model predates compaction and should be gated off",
                NativeCompactionModelGate.supportsServerSideCompaction(ClientType.OPENAI, model)
            )
        }
    }

    @Test
    fun `older openai models fall back to the app side path`() {
        // This is the documented single exception to "ClientType.OPENAI never calls the app-side
        // compaction service": degrade rather than fail, so a user who typed an old name can chat.
        listOf("gpt-4o", "gpt-4.1", "gpt-4-turbo", "o3", "o1-preview", "gpt-3.5-turbo").forEach { model ->
            assertFalse(
                "$model should be gated off",
                NativeCompactionModelGate.supportsServerSideCompaction(ClientType.OPENAI, model)
            )
        }
    }

    @Test
    fun `unreleased families default to unsupported until added by hand`() {
        assertFalse(NativeCompactionModelGate.supportsServerSideCompaction(ClientType.OPENAI, "gpt-5.5"))
        assertFalse(NativeCompactionModelGate.supportsServerSideCompaction(ClientType.OPENAI, "gpt-6"))
    }

    @Test
    fun `unknown or misspelled model names default to unsupported`() {
        listOf("gtp-5.2", "gpt5.2", "my-custom-alias", "future-model-9", "").forEach { model ->
            assertFalse(
                "$model should default to unsupported",
                NativeCompactionModelGate.supportsServerSideCompaction(ClientType.OPENAI, model)
            )
        }
    }

    @Test
    fun `no non-openai client type may ever use server side compaction`() {
        // Including CUSTOM, which is how DeepSeek-style third-party services are configured in this
        // app. A gateway serving a model literally named "gpt-5.2" still must not receive
        // context_management.
        ClientType.entries
            .filter { it != ClientType.OPENAI }
            .forEach { clientType ->
                listOf("gpt-5.4", "gpt-5.2", "claude-sonnet-4", "deepseek-chat").forEach { model ->
                    assertFalse(
                        "$clientType/$model must not be gated on",
                        NativeCompactionModelGate.supportsServerSideCompaction(clientType, model)
                    )
                }
            }
    }

    @Test
    fun `gateway style prefixes are stripped before matching`() {
        assertTrue(NativeCompactionModelGate.supportsServerSideCompaction(ClientType.OPENAI, "openai/gpt-5.2"))
    }

    @Test
    fun `matching is case and whitespace insensitive`() {
        assertTrue(NativeCompactionModelGate.supportsServerSideCompaction(ClientType.OPENAI, "  GPT-5.2  "))
    }
}
