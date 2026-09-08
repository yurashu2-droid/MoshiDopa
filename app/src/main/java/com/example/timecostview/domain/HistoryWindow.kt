package com.example.timecostview.domain

import java.time.LocalDate
import kotlin.math.abs

object HistoryWindow {
    fun weekStart(day: LocalDate): LocalDate =
        day.minusDays((day.dayOfWeek.value % 7).toLong())

    /**
     * A deliberate drag keeps the nearest day at the left edge. A real fling
     * moves to the adjacent calendar week so the result is always Sunday–Saturday.
     */
    fun settleAnchor(
        nearestVisibleStart: LocalDate,
        velocityX: Float,
        durationMs: Long,
        distancePx: Float,
        columnWidthPx: Int,
        fastVelocityPx: Int,
    ): LocalDate {
        val hasIntentionalDistance = distancePx >= columnWidthPx * 0.75f
        val hasFastVelocity = abs(velocityX) >= fastVelocityPx
        val isQuickLongStroke = durationMs <= 260L && distancePx >= columnWidthPx * 2f
        val isFast = hasIntentionalDistance && (hasFastVelocity || isQuickLongStroke)
        if(!isFast) return nearestVisibleStart

        val week = weekStart(nearestVisibleStart)
        return week.plusDays(if(velocityX < 0f) 7L else -7L)
    }
}
