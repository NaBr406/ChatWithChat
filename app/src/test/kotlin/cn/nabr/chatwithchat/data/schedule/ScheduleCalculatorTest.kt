package cn.nabr.chatwithchat.data.schedule

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleCalculatorTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val calculator = ScheduleCalculator(zoneId)

    @Test
    fun `one shot returns future timestamp once`() {
        assertEquals(200L, calculator.nextRunAt(ScheduleSpec.OneShotAt(200L), nowEpochSeconds = 100L))
        assertNull(calculator.nextRunAt(ScheduleSpec.OneShotAt(100L), nowEpochSeconds = 100L))
        assertNull(calculator.nextRunAt(ScheduleSpec.OneShotAt(90L), nowEpochSeconds = 100L))
    }

    @Test
    fun `interval advances from anchor without returning now`() {
        val spec = ScheduleSpec.Interval(anchorEpochSeconds = 100L, everySeconds = 60L)

        assertEquals(100L, calculator.nextRunAt(spec, nowEpochSeconds = 90L))
        assertEquals(160L, calculator.nextRunAt(spec, nowEpochSeconds = 100L))
        assertEquals(220L, calculator.nextRunAt(spec, nowEpochSeconds = 161L))
    }

    @Test
    fun `invalid interval has no next run`() {
        assertNull(
            calculator.nextRunAt(
                ScheduleSpec.Interval(anchorEpochSeconds = 100L, everySeconds = 0L),
                nowEpochSeconds = 100L
            )
        )
    }

    @Test
    fun `daily local time returns today when still future`() {
        val now = epoch("2026-07-09T08:00:00")
        val expected = epoch("2026-07-09T09:30:00")

        assertEquals(
            expected,
            calculator.nextRunAt(ScheduleSpec.DailyLocalTime(hour = 9, minute = 30), nowEpochSeconds = now)
        )
    }

    @Test
    fun `daily local time rolls to tomorrow after time passed`() {
        val now = epoch("2026-07-09T10:00:00")
        val expected = epoch("2026-07-10T09:30:00")

        assertEquals(
            expected,
            calculator.nextRunAt(ScheduleSpec.DailyLocalTime(hour = 9, minute = 30), nowEpochSeconds = now)
        )
    }

    @Test
    fun `weekly local time returns target day in current week`() {
        val now = epoch("2026-07-09T10:00:00")
        val expected = epoch("2026-07-10T09:30:00")

        assertEquals(
            expected,
            calculator.nextRunAt(
                ScheduleSpec.WeeklyLocalTime(DayOfWeek.FRIDAY, hour = 9, minute = 30),
                nowEpochSeconds = now
            )
        )
    }

    @Test
    fun `weekly local time rolls to next week when target already passed`() {
        val now = epoch("2026-07-10T10:00:00")
        val expected = epoch("2026-07-17T09:30:00")

        assertEquals(
            expected,
            calculator.nextRunAt(
                ScheduleSpec.WeeklyLocalTime(DayOfWeek.FRIDAY, hour = 9, minute = 30),
                nowEpochSeconds = now
            )
        )
    }

    @Test
    fun `cron expression is stored but not parsed in foundation slice`() {
        assertNull(
            calculator.nextRunAt(
                ScheduleSpec.CronExpression("0 9 * * MON"),
                nowEpochSeconds = epoch("2026-07-09T10:00:00")
            )
        )
    }

    @Test
    fun `disabled scheduled task has no initial next run`() {
        val task = ScheduledTask(
            taskId = "reminder-1",
            title = "Reminder",
            spec = ScheduleSpec.OneShotAt(200L),
            enabled = false
        )

        assertNull(ScheduleCalculator.initialNextRunAt(task, nowEpochSeconds = 100L, zoneId = zoneId))
    }

    private fun epoch(localDateTime: String): Long =
        LocalDateTime.parse(localDateTime).atZone(zoneId).toEpochSecond()
}
