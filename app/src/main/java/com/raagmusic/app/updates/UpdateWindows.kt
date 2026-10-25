package com.raagmusic.app.updates

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Two local-time windows; Android may defer background work, so foreground checks catch up. */
internal object UpdateWindows {
    private val morning = LocalTime.of(8, 0)
    private val evening = LocalTime.of(19, 0)
    fun token(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val local = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return when {
            local.toLocalTime() < morning -> "${local.toLocalDate().minusDays(1)}:evening"
            local.toLocalTime() < evening -> "${local.toLocalDate()}:morning"
            else -> "${local.toLocalDate()}:evening"
        }
    }
    fun sameWindow(first: Long, second: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        first > 0 && first <= second && token(first, zone) == token(second, zone)

    fun nextStart(now: ZonedDateTime, isMorning: Boolean): ZonedDateTime {
        val time = if (isMorning) morning else evening
        val today = now.toLocalDate().atTime(time).atZone(now.zone)
        return if (today > now) today else now.toLocalDate().plusDays(1).atTime(time).atZone(now.zone)
    }
}
