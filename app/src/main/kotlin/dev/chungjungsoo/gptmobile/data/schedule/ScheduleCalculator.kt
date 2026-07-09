package dev.chungjungsoo.gptmobile.data.schedule

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleCalculator(
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {
    fun nextRunAt(
        spec: ScheduleSpec,
        nowEpochSeconds: Long
    ): Long? = when (spec) {
        is ScheduleSpec.OneShotAt -> nextOneShotRunAt(spec, nowEpochSeconds)
        is ScheduleSpec.Interval -> nextIntervalRunAt(spec, nowEpochSeconds)
        is ScheduleSpec.DailyLocalTime -> nextDailyRunAt(spec, nowEpochSeconds)
        is ScheduleSpec.WeeklyLocalTime -> nextWeeklyRunAt(spec, nowEpochSeconds)
        is ScheduleSpec.CronExpression -> null
    }

    private fun nextOneShotRunAt(
        spec: ScheduleSpec.OneShotAt,
        nowEpochSeconds: Long
    ): Long? =
        spec.runAtEpochSeconds.takeIf { runAt -> runAt > nowEpochSeconds }

    private fun nextIntervalRunAt(
        spec: ScheduleSpec.Interval,
        nowEpochSeconds: Long
    ): Long? {
        if (spec.everySeconds <= 0) return null
        if (nowEpochSeconds < spec.anchorEpochSeconds) return spec.anchorEpochSeconds

        val elapsed = nowEpochSeconds - spec.anchorEpochSeconds
        val completedIntervals = Math.floorDiv(elapsed, spec.everySeconds) + 1
        return spec.anchorEpochSeconds + completedIntervals * spec.everySeconds
    }

    private fun nextDailyRunAt(
        spec: ScheduleSpec.DailyLocalTime,
        nowEpochSeconds: Long
    ): Long? {
        val time = localTimeOrNull(spec.hour, spec.minute) ?: return null
        val now = nowEpochSeconds.toZonedDateTime()
        val todayRun = LocalDateTime.of(now.toLocalDate(), time).atZone(zoneId)
        return if (todayRun.toEpochSecond() > nowEpochSeconds) {
            todayRun.toEpochSecond()
        } else {
            todayRun.plusDays(1).toEpochSecond()
        }
    }

    private fun nextWeeklyRunAt(
        spec: ScheduleSpec.WeeklyLocalTime,
        nowEpochSeconds: Long
    ): Long? {
        val time = localTimeOrNull(spec.hour, spec.minute) ?: return null
        val now = nowEpochSeconds.toZonedDateTime()
        val daysUntilTarget = (spec.dayOfWeek.value - now.dayOfWeek.value).floorMod(DAYS_IN_WEEK)
        val targetDate = now.toLocalDate().plusDays(daysUntilTarget.toLong())
        val targetRun = LocalDateTime.of(targetDate, time).atZone(zoneId)
        return if (targetRun.toEpochSecond() > nowEpochSeconds) {
            targetRun.toEpochSecond()
        } else {
            targetRun.plusWeeks(1).toEpochSecond()
        }
    }

    private fun Long.toZonedDateTime(): ZonedDateTime =
        Instant.ofEpochSecond(this).atZone(zoneId)

    private fun localTimeOrNull(hour: Int, minute: Int): LocalTime? =
        runCatching { LocalTime.of(hour, minute) }.getOrNull()

    private fun Int.floorMod(other: Int): Int =
        Math.floorMod(this, other)

    companion object {
        private const val DAYS_IN_WEEK = 7

        fun nextRunAt(
            spec: ScheduleSpec,
            nowEpochSeconds: Long,
            zoneId: ZoneId = ZoneId.systemDefault()
        ): Long? = ScheduleCalculator(zoneId).nextRunAt(spec, nowEpochSeconds)

        fun initialNextRunAt(
            task: ScheduledTask,
            nowEpochSeconds: Long,
            zoneId: ZoneId = ZoneId.systemDefault()
        ): Long? =
            if (task.enabled) {
                nextRunAt(task.spec, nowEpochSeconds, zoneId)
            } else {
                null
            }
    }
}
