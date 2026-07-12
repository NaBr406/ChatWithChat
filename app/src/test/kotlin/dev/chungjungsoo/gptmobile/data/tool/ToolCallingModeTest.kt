package dev.chungjungsoo.gptmobile.data.tool

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCallingModeTest {
    @Test
    fun `missing stored value defaults to auto`() {
        assertEquals(ToolCallingMode.Auto, ToolCallingMode.fromStorageValue(null))
    }

    @Test
    fun `unknown stored value defaults to auto`() {
        assertEquals(ToolCallingMode.Auto, ToolCallingMode.fromStorageValue("unknown"))
    }

    @Test
    fun `explicit off remains off`() {
        assertEquals(ToolCallingMode.Off, ToolCallingMode.fromStorageValue("off"))
    }
}
