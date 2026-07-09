package dev.chungjungsoo.gptmobile.data.schedule

import java.time.DayOfWeek

sealed interface ScheduleSpec {
    data class OneShotAt(
        val runAtEpochSeconds: Long
    ) : ScheduleSpec

    data class Interval(
        val anchorEpochSeconds: Long,
        val everySeconds: Long
    ) : ScheduleSpec

    data class DailyLocalTime(
        val hour: Int,
        val minute: Int
    ) : ScheduleSpec

    data class WeeklyLocalTime(
        val dayOfWeek: DayOfWeek,
        val hour: Int,
        val minute: Int
    ) : ScheduleSpec

    data class CronExpression(
        val expression: String
    ) : ScheduleSpec
}
