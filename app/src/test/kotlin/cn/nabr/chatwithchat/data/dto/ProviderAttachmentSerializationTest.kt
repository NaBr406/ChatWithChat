package cn.nabr.chatwithchat.data.dto

import cn.nabr.chatwithchat.data.dto.anthropic.common.ImageSource
import cn.nabr.chatwithchat.data.dto.google.common.Part
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseContentPart
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAttachmentSerializationTest {
    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    @Test
    fun `openai response image file serializes file_id without image_url`() {
        val payload = json.encodeToString(ResponseContentPart.imageFile("file-123"))

        assertTrue(payload.contains("\"file_id\":\"file-123\""))
        assertFalse(payload.contains("image_url"))
    }

    @Test
    fun `anthropic image file serializes file_id without base64 fields`() {
        val payload = json.encodeToString(ImageSource.file("file_123"))

        assertTrue(payload.contains("\"file_id\":\"file_123\""))
        assertFalse(payload.contains("media_type"))
        assertFalse(payload.contains("\"data\""))
    }

    @Test
    fun `google file part serializes file_uri`() {
        val payload = json.encodeToString(Part.fileData("image/png", "google-uri"))

        assertTrue(payload.contains("\"file_data\""))
        assertTrue(payload.contains("\"file_uri\":\"google-uri\""))
        assertFalse(payload.contains("inline_data"))
    }
}
