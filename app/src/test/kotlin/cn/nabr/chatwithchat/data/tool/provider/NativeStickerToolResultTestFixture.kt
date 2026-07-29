package cn.nabr.chatwithchat.data.tool.provider

import cn.nabr.chatwithchat.data.tool.ToolResult
import cn.nabr.chatwithchat.data.tool.ToolSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

internal fun nativeStickerSearchResult(callId: String): ToolResult = ToolResult(
    callId = callId,
    name = "search_stickers",
    content = "sticker_id=$NATIVE_STICKER_ID; $SENSITIVE_PATH_TOKEN; $SENSITIVE_URI_TOKEN; $SENSITIVE_HASH_TOKEN; $SENSITIVE_BYTES_TOKEN",
    metadata = mapOf(
        "asset_path" to SENSITIVE_PATH_TOKEN,
        "content_uri" to SENSITIVE_URI_TOKEN,
        "asset_hash" to SENSITIVE_HASH_TOKEN,
        "bytes" to SENSITIVE_BYTES_TOKEN
    ),
    structuredContent = buildJsonObject {
        put(
            "candidates",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("sticker_id", NATIVE_STICKER_ID)
                        put("title", "开心企鹅")
                        put("alt_text", "一只开心挥手的企鹅")
                        put("tags", JsonArray(listOf(JsonPrimitive("开心"), JsonPrimitive("问候"))))
                        put("asset_path", SENSITIVE_PATH_TOKEN)
                        put("content_uri", SENSITIVE_URI_TOKEN)
                        put("asset_hash", SENSITIVE_HASH_TOKEN)
                        put("bytes", SENSITIVE_BYTES_TOKEN)
                    }
                )
            )
        )
        put("catalog_path", SENSITIVE_PATH_TOKEN)
    },
    sources = listOf(
        ToolSource.PublicUrl(
            title = "private sticker source",
            url = SENSITIVE_SOURCE_TOKEN
        )
    )
)

internal fun assertCompactNativeStickerProjection(payload: String, canonicalResult: ToolResult) {
    assertEquals(1, payload.occurrencesOf(NATIVE_STICKER_ID))
    assertTrue(payload.contains("structured_content"))
    assertTrue(payload.contains("title"))
    assertTrue(payload.contains("alt_text"))
    assertTrue(payload.contains("tags"))
    assertFalse(payload.contains(SENSITIVE_PATH_TOKEN))
    assertFalse(payload.contains(SENSITIVE_URI_TOKEN))
    assertFalse(payload.contains(SENSITIVE_HASH_TOKEN))
    assertFalse(payload.contains(SENSITIVE_BYTES_TOKEN))
    assertFalse(payload.contains(SENSITIVE_SOURCE_TOKEN))
    assertFalse(payload.contains("asset_path"))
    assertFalse(payload.contains("content_uri"))
    assertFalse(payload.contains("asset_hash"))
    assertFalse(payload.contains("bytes"))
    assertFalse(payload.contains("catalog_path"))
    assertFalse(payload.contains("public_url"))

    assertTrue(canonicalResult.content.contains(NATIVE_STICKER_ID))
    assertTrue(canonicalResult.structuredContent.toString().contains(NATIVE_STICKER_ID))
    assertTrue(canonicalResult.structuredContent.toString().contains(SENSITIVE_PATH_TOKEN))
}

private fun String.occurrencesOf(value: String): Int = windowed(value.length).count { candidate -> candidate == value }

private const val NATIVE_STICKER_ID = "builtin.reactions.native_projection_fixture"
private const val SENSITIVE_PATH_TOKEN = "private-candidate.png"
private const val SENSITIVE_URI_TOKEN = "content://stickers/private-candidate"
private const val SENSITIVE_HASH_TOKEN = "asset-hash-must-not-leak"
private const val SENSITIVE_BYTES_TOKEN = "base64-bytes-must-not-leak"
private const val SENSITIVE_SOURCE_TOKEN = "https://source-must-not-leak.invalid/sticker"
