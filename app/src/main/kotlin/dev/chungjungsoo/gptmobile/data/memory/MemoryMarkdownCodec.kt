package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.database.entity.PersonalMemory

class MemoryMarkdownCodec {
    fun encode(memories: List<PersonalMemory>): String {
        val grouped = memories
            .sortedWith(compareBy<PersonalMemory> { it.type }.thenByDescending { it.importance })
            .groupBy { sectionTitle(it.type) }

        return buildString {
            appendLine("# 用户记忆")
            grouped.forEach { (title, sectionMemories) ->
                appendLine()
                appendLine("## $title")
                sectionMemories.forEach { memory ->
                    append("- ${memory.recallText}")
                    if (memory.status.isNotBlank()) {
                        append(" 状态：${statusTitle(memory.status)}")
                    }
                    appendLine()
                }
            }
        }.trimEnd()
    }

    private fun sectionTitle(type: String): String = when (type) {
        "stable_profile" -> "个人资料"
        "communication_style" -> "沟通风格"
        "interest" -> "兴趣"
        "important_event" -> "重要事件"
        "important_person" -> "重要人物"
        "emotional_pattern" -> "情绪模式"
        "boundary" -> "边界"
        "life_context" -> "生活背景"
        "recurring_theme" -> "重复主题"
        "light_productivity_preference" -> "轻量生产力偏好"
        else -> "其他类型"
    }

    private fun statusTitle(status: String): String = when (status) {
        MemoryStatus.ACTIVE -> "生效中"
        MemoryStatus.PENDING_CONFIRMATION -> "待确认"
        MemoryStatus.RESOLVED -> "已解决"
        MemoryStatus.ARCHIVED -> "已归档"
        MemoryStatus.SUPERSEDED -> "已替换"
        else -> "未知状态"
    }
}
