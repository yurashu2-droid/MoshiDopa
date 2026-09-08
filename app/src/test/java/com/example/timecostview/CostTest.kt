package com.example.timecostview

import com.example.timecostview.domain.Cost
import com.example.timecostview.domain.Record
import com.example.timecostview.domain.splitAtMidnight
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CostTest {
    @Test fun hourlyTick() { assertEquals(0.05, Cost.yen(100, 1800.0), 0.000001) }
    @Test fun twentyEightMinutes() { assertEquals(840.0, Cost.yen(28 * 60 * 1000L, 1800.0), 0.000001) }
    @Test fun monthlyConversion() { assertEquals(2000.0, Cost.hourly(320000.0, true, 160.0), 0.000001) }
    @Test(expected = IllegalArgumentException::class) fun invalidHours() { Cost.hourly(300000.0, true, 0.0) }
    @Test(expected = IllegalArgumentException::class) fun invalidAmount() { Cost.hourly(Double.NaN, false, 160.0) }
    @Test fun longDuration() { assertEquals("27:00:00", Cost.time(27 * 3600000L)) }
    @Test fun negativeElapsed() { assertEquals(0.0, Cost.yen(-100, 1800.0), 0.0) }
    @Test fun coffeePurchaseMessage() {
        assertEquals("コーヒーが買えました", Cost.purchaseMessage(200.0))
        assertEquals("コーヒーまであと¥100.00", Cost.purchaseMessage(100.0))
    }
    @Test fun correctInvestmentExample() { assertEquals(5765.0, Cost.yen((3 * 3600 + 12 * 60 + 10) * 1000L, 1800.0), 0.000001) }
    @Test fun midnightKeepsDurationAndCost() {
        val start = Instant.parse("2026-09-05T14:59:59Z").toEpochMilli()
        val record = Record(1, "app", "App", "SPEND", start, start + 3000, 3000, 1800.0)
        val parts = splitAtMidnight(record, ZoneId.of("Asia/Tokyo"))
        assertEquals(listOf(1000L, 2000L), parts.map { it.duration })
        assertEquals(record.amount, parts.sumOf { it.amount }, 0.000001)
    }
    @Test fun daylightSavingDayIsNotAssumed24Hours() {
        val start = Instant.parse("2026-03-08T05:00:00Z").toEpochMilli()
        val record = Record(1, "app", "App", "SPEND", start, start + 86400000, 86400000, 1800.0)
        assertEquals(listOf(23 * 3600000L, 3600000L), splitAtMidnight(record, ZoneId.of("America/New_York")).map { it.duration })
    }
}
