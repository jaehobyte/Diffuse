package com.diffuse.feature.browse

import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * specs/browse.md: metadata reads as "3분 전", "어제". Beyond a week the relative form stops
 * helping, so it falls back to a day count rather than growing indefinitely.
 */
object RelativeTime {

    private const val DAYS_BEFORE_ABSOLUTE = 7

    fun format(context: Context, thenMillis: Long, nowMillis: Long): String {
        val elapsed = (nowMillis - thenMillis).coerceAtLeast(0)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
        val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
        val days = TimeUnit.MILLISECONDS.toDays(elapsed)
        return when {
            minutes < 1 -> context.getString(R.string.browse_time_just_now)
            hours < 1 -> context.getString(R.string.browse_time_minutes, minutes.toInt())
            days < 1 -> context.getString(R.string.browse_time_hours, hours.toInt())
            days == 1L -> context.getString(R.string.browse_time_yesterday)
            days < DAYS_BEFORE_ABSOLUTE -> context.getString(R.string.browse_time_days, days.toInt())
            else -> context.getString(R.string.browse_time_days, days.toInt())
        }
    }
}
