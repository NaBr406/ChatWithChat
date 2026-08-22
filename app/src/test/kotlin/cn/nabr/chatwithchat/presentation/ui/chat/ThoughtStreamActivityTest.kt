package cn.nabr.chatwithchat.presentation.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThoughtStreamActivityTest {

    @Test
    fun `growing thoughts mark the reasoning stream active`() {
        val activity = ThoughtStreamActivity(thoughts = "", text = "")

        assertTrue(activity.update(thoughts = "Checking the", text = ""))
        assertTrue(activity.update(thoughts = "Checking the README", text = ""))
    }

    @Test
    fun `growing answer text ends the reasoning segment`() {
        val activity = ThoughtStreamActivity(thoughts = "", text = "")
        activity.update(thoughts = "Reasoning", text = "")

        assertFalse(activity.update(thoughts = "Reasoning", text = "Here is what"))
    }

    @Test
    fun `reasoning resuming after answer text becomes active again`() {
        val activity = ThoughtStreamActivity(thoughts = "", text = "")
        activity.update(thoughts = "First round reasoning", text = "")
        activity.update(thoughts = "First round reasoning", text = "First round answer")

        // 这是分步执行的第二轮：正文已经落地了，推理又回来了。
        // 旧的 text.isBlank() 判断在这里会永久为假，平滑动画退化成整批瞬移。
        assertTrue(
            activity.update(
                thoughts = "First round reasoningSecond round reasoning",
                text = "First round answer"
            )
        )
    }

    @Test
    fun `unchanged text and thoughts keep the previous verdict`() {
        val activity = ThoughtStreamActivity(thoughts = "", text = "")
        activity.update(thoughts = "Reasoning", text = "")

        assertTrue(activity.update(thoughts = "Reasoning", text = ""))
        assertTrue(activity.update(thoughts = "Reasoning", text = ""))
    }

    @Test
    fun `a publish that grows both sides keeps reasoning active for one more publish`() {
        val activity = ThoughtStreamActivity(thoughts = "", text = "")
        activity.update(thoughts = "Reasoning", text = "")

        assertTrue(activity.update(thoughts = "Reasoning more", text = "Answer"))
        assertFalse(activity.update(thoughts = "Reasoning more", text = "Answer continues"))
    }

    @Test
    fun `a restored message with existing thoughts starts active`() {
        val activity = ThoughtStreamActivity(thoughts = "Recovered reasoning", text = "")

        assertTrue(activity.update(thoughts = "Recovered reasoning", text = ""))
    }
}
