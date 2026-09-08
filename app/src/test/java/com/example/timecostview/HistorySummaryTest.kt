package com.example.timecostview

import com.example.timecostview.domain.*
import java.time.*
import org.junit.Test
import org.junit.Assert.*

class HistorySummaryTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val start = LocalDate.of(2026, 9, 7).atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
    @Test fun midnightKeepsMoneyAndSourceId() {
        val record = Record(12, "video", "Video", "SPEND", start, start+3600000, 3600000, 2000.0)
        val days = HistorySummary.days(listOf(record), zone)
        assertEquals(2, days.size)
        assertEquals(2000.0, days.values.flatten().sumOf { it.amount }, .0001)
        assertTrue(days.values.flatten().all { it.id == 12L })
    }
    @Test fun groupsPreserveRatesAndSeparateManualActivitiesAndModes() {
        fun r(id: Long, app: String, title: String, mode: String, rate: Double) =
            Record(id, app, title, mode, start, start+60000, 60000, rate)
        val groups = HistorySummary.groups(listOf(
            r(1,"video","Video","SPEND",1200.0), r(2,"video","Video renamed","SPEND",2400.0),
            r(3,"manual","Study","INVEST",1200.0), r(4,"manual","Read","INVEST",1200.0),
            r(5,"video","Video","INVEST",1200.0)))
        assertEquals(4, groups.size)
        val video = groups.first { it.records.size == 2 }
        assertEquals(60.0, video.amount, .0001)
        assertEquals(video.amount, video.receipt().amount, .0001)
        assertEquals(120000L, video.receipt().duration)
    }
}
