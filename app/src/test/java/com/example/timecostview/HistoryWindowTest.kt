package com.example.timecostview

import com.example.timecostview.domain.HistoryWindow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class HistoryWindowTest {
    private val thursday = LocalDate.of(2026, 9, 10)

    @Test
    fun slowDrag_staysOnNearestArbitraryDay() {
        assertEquals(
            thursday,
            HistoryWindow.settleAnchor(thursday, -800f, 1200L, 70f, 44, 1200),
        )
    }

    @Test
    fun fastSwipeLeft_movesToNextSunday() {
        assertEquals(
            LocalDate.of(2026, 9, 13),
            HistoryWindow.settleAnchor(thursday, -1400f, 180L, 70f, 44, 1200),
        )
    }

    @Test
    fun fastSwipeRight_movesToPreviousSunday() {
        assertEquals(
            LocalDate.of(2026, 8, 30),
            HistoryWindow.settleAnchor(thursday, 1400f, 180L, 70f, 44, 1200),
        )
    }

    @Test
    fun shortFlick_isNotMisclassifiedAsFastSwipe() {
        assertEquals(
            thursday,
            HistoryWindow.settleAnchor(thursday, -1800f, 120L, 20f, 44, 1200),
        )
    }

    @Test
    fun quickLongStroke_isFastEvenWhenVelocitySamplingIsLow() {
        assertEquals(
            LocalDate.of(2026, 9, 13),
            HistoryWindow.settleAnchor(thursday, -500f, 180L, 120f, 44, 1200),
        )
    }
}
