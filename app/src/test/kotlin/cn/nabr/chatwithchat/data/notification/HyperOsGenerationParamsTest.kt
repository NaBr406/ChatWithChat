package cn.nabr.chatwithchat.data.notification

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperOsGenerationParamsTest {
    @Test
    fun generationUsesDocumentedTemplateAndReferencesBundledResources() {
        val payload = HyperOsGenerationParams.build("正在生成回答", "正在处理 2 个会话", "生成中", true)
        val params = Json.parseToJsonElement(payload).jsonObject.getValue("param_v2").jsonObject
        val island = params.getValue("param_island").jsonObject
        val large = island.getValue("bigIslandArea").jsonObject
        val text = large.getValue("imageTextInfoLeft").jsonObject.getValue("textInfo").jsonObject
        assertEquals("生成中", text.getValue("title").jsonPrimitive.content)
        assertEquals(HyperOsGenerationParams.ICON_KEY, large.getValue("picInfo").jsonObject.getValue("pic").jsonPrimitive.content)
        assertEquals(large.getValue("picInfo"), island.getValue("smallIslandArea").jsonObject.getValue("picInfo"))
        assertEquals(HyperOsGenerationParams.STOP_KEY, params.getValue("actions").jsonArray.single().jsonObject.getValue("action").jsonPrimitive.content)
        assertEquals("false", params.getValue("filterWhenNoPermission").jsonPrimitive.content)
        assertEquals("reopen", params.getValue("reopen").jsonPrimitive.content)
        assertTrue(payload.toByteArray(Charsets.UTF_8).size < 3072)
    }

    @Test
    fun savingRemovesStopAndEscapesLocalizedText() {
        val title = "Saving \"answers\"\n保存中"
        val payload = HyperOsGenerationParams.build(title, "Working on 2147483647 chats.", "Saving", false)
        val params = Json.parseToJsonElement(payload).jsonObject.getValue("param_v2").jsonObject
        assertFalse(params.containsKey("actions"))
        assertEquals(title, params.getValue("baseInfo").jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals(title, params.getValue("aodTitle").jsonPrimitive.content)
        assertEquals("false", params.getValue("enableFloat").jsonPrimitive.content)
        assertTrue(payload.toByteArray(Charsets.UTF_8).size < 3072)
    }
}
