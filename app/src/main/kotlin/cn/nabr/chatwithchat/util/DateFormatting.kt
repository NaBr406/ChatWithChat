package cn.nabr.chatwithchat.util

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Uses the calendar year; uppercase `YYYY` is a week-based year and is wrong around New Year. */
private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

internal fun formatEpochSecondsAsDateTime(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochSecond(epochSeconds).atZone(zone).format(DATE_TIME_FORMATTER)

internal fun formatEpochSecondsAsDate(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochSecond(epochSeconds).atZone(zone).format(DATE_FORMATTER)

internal fun formatEpochMillisAsDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).format(DATE_FORMATTER)

internal fun formatCurrentDate(clock: Clock = Clock.systemDefaultZone()): String =
    LocalDate.now(clock).format(DATE_FORMATTER)
