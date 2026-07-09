package dev.chungjungsoo.gptmobile.data.schedule

data class ScheduledTask(
    val taskId: String,
    val title: String,
    val spec: ScheduleSpec,
    val enabled: Boolean = true,
    val nextRunAt: Long? = null,
    val payloadJson: String = "{}"
)
