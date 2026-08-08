package cn.nabr.chatwithchat.data.dto.openai.request

import cn.nabr.chatwithchat.data.network.NetworkClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 1: pins the exact wire format of the compaction fields.
 *
 * These names come straight from the official Responses API contract. Getting one wrong does not
 * degrade gracefully — it fails every request for the affected provider — and `encodeDefaults = true`
 * on the shared JSON config means a missing `@EncodeDefault(NEVER)` would start sending `null`s to
 * providers that reject them.
 */
class ResponsesRequestCompactionTest {

    private val json = NetworkClient.openAIJson

    private fun request(
        contextManagement: List<ResponseContextManagement>? = null,
        store: Boolean? = null
    ) = ResponsesRequest(
        model = "gpt-5.2",
        input = listOf(
            ResponseInputMessage(role = "user", content = ResponseInputContent.text("hi"))
        ),
        contextManagement = contextManagement,
        store = store
    )

    @Test
    fun `compaction config serializes to the official field names`() {
        val encoded = json.encodeToString(
            request(contextManagement = listOf(ResponseContextManagement.compaction(88_320)), store = false)
        )

        assertTrue(encoded, encoded.contains("\"context_management\":[{"))
        assertTrue(encoded, encoded.contains("\"type\":\"compaction\""))
        assertTrue(encoded, encoded.contains("\"compact_threshold\":88320"))
        assertTrue(encoded, encoded.contains("\"store\":false"))
    }

    @Test
    fun `a request without compaction omits both fields entirely`() {
        // Every non-OPENAI client type takes this path. Emitting `"context_management":null` would
        // be rejected by some gateways, so the fields must be absent, not null.
        val encoded = json.encodeToString(request())

        assertFalse(encoded, encoded.contains("context_management"))
        assertFalse(encoded, encoded.contains("store"))
    }

    @Test
    fun `store is emitted when explicitly set to false`() {
        val encoded = json.encodeToString(request(store = false))

        assertTrue(encoded, encoded.contains("\"store\":false"))
    }

    @Test
    fun `compact endpoint request omits store unless set`() {
        val withoutStore = json.encodeToString(
            ResponsesCompactRequest(
                model = "gpt-5.2",
                input = listOf(ResponseInputMessage("user", ResponseInputContent.text("hi")))
            )
        )
        assertFalse(withoutStore, withoutStore.contains("store"))

        val withStore = json.encodeToString(
            ResponsesCompactRequest(
                model = "gpt-5.2",
                input = listOf(ResponseInputMessage("user", ResponseInputContent.text("hi"))),
                store = false
            )
        )
        assertTrue(withStore, withStore.contains("\"store\":false"))
    }

    @Test
    fun `opaque compaction items round trip byte for byte through passthrough`() {
        // The compaction item's contents are deliberately unspecified. Replaying it must not lose,
        // reorder or reinterpret anything, so it travels as a passthrough JsonObject.
        val nativeItem = """{"type":"compaction","id":"cmp_abc123","encrypted_content":"AAECAwQ="}"""
        val decoded = json.decodeFromString<ResponseInputItem>(nativeItem)

        assertTrue("expected a passthrough item, got ${decoded::class.simpleName}", decoded is ResponsePassthroughInputItem)

        val reEncoded = json.encodeToString(ResponseInputItemSerializer, decoded)
        assertEquals(
            json.parseToJsonElement(nativeItem),
            json.parseToJsonElement(reEncoded)
        )
    }

    @Test
    fun `a compaction item survives a full request round trip`() {
        val nativeItem = """{"type":"compaction","id":"cmp_1","encrypted_content":"Zm9v"}"""
        val encoded = json.encodeToString(
            ResponsesRequest(
                model = "gpt-5.2",
                input = listOf(
                    json.decodeFromString<ResponseInputItem>(nativeItem),
                    ResponseInputMessage("user", ResponseInputContent.text("next question"))
                ),
                contextManagement = listOf(ResponseContextManagement.compaction(88_320)),
                store = false
            )
        )

        assertTrue(encoded, encoded.contains("\"encrypted_content\":\"Zm9v\""))
        assertTrue(encoded, encoded.contains("\"next question\""))
    }

    @Test
    fun `compaction threshold is omitted when null`() {
        val encoded = json.encodeToString(ResponseContextManagement())

        assertTrue(encoded, encoded.contains("\"type\":\"compaction\""))
        assertFalse(encoded, encoded.contains("compact_threshold"))
    }
}
