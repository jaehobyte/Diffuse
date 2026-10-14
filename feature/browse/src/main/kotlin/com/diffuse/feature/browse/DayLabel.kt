package com.diffuse.feature.browse

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * DESIGN.md §4 (Browse): the Gallery groups its grid under day headers — `오늘`, `어제`, then the
 * date. Projects are grouped by the calendar day of their last edit in the device zone.
 */
object DayLabel {

    fun dayOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun format(context: Context, day: LocalDate, today: LocalDate): String = when {
        day == today -> context.getString(R.string.browse_section_today)
        day == today.minusDays(1) -> context.getString(R.string.browse_time_yesterday)
        day.year == today.year -> context.getString(R.string.browse_section_date, day.monthValue, day.dayOfMonth)
        else -> context.getString(R.string.browse_section_date_year, day.year, day.monthValue, day.dayOfMonth)
    }
}
