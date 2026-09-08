package com.example.timecostview

import com.example.timecostview.domain.Record
import com.example.timecostview.domain.StatementBuilder
import com.example.timecostview.domain.StatementPeriod
import com.example.timecostview.domain.HistorySummary
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatementTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val september = LocalDate.of(2026, 9, 30).atTime(23, 59).atZone(zone).toInstant().toEpochMilli()

    @Test fun monthBoundaryPreservesSavedRatesAndAllModes() {
        val spend = Record(1, "video", "YouTube", "SPEND", september, september + 120_000, 120_000, 1800.0)
        val invest = Record(2, "manual", "個人開発", "INVEST", september, september + 60_000, 60_000, 2400.0, "もしドパ", "個人開発", "共有カードを調整")
        val months = HistorySummary.months(listOf(spend, invest), zone)
        assertEquals(2, months.size)
        assertEquals(70.0, months[YearMonth.of(2026, 9)]!!.sumOf { it.amount }, 0.0001)
        assertEquals(30.0, months[YearMonth.of(2026, 10)]!!.filter { it.mode == "SPEND" }.sumOf { it.amount }, 0.0001)
        assertEquals(3, months.values.flatten().size)
    }

    @Test fun investStatementCarriesProjectAndNotesWithoutSpendDecoration() {
        val day = LocalDate.of(2026, 9, 7)
        val start = day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val record = Record(8, "manual", "個人開発", "INVEST", start, start + 3_600_000, 3_600_000, 1800.0, "もしドパ", "個人開発", "明細画面を実装")
        val statement = StatementBuilder.day(listOf(record), day, "INVEST", zone)
        assertEquals(StatementPeriod.DAY, statement.period)
        assertEquals("今日の自己投資明細", statement.title)
        assertEquals("もしドパ", statement.project)
        assertEquals(listOf("明細画面を実装"), statement.notes)
        assertFalse(statement.spend)
        assertTrue(statement.lines.single().label.contains("もしドパ"))
    }

    @Test fun statementSeparatesSpendAndInvestTotals() {
        val day = LocalDate.of(2026, 9, 7)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val records = listOf(
            Record(1, "video", "動画", "SPEND", start, start + 1_000, 1_000, 1800.0),
            Record(2, "manual", "個人開発", "INVEST", start, start + 2_000, 2_000, 1800.0, "app", "個人開発", "テスト"),
        )
        assertEquals(0.5, StatementBuilder.day(records, day, "SPEND", zone).amount, 0.0001)
        assertEquals(1.0, StatementBuilder.day(records, day, "INVEST", zone).amount, 0.0001)
    }

    @Test fun emptyInvestPeriodStillUsesInvestStatementTitle() {
        val day = LocalDate.of(2026, 9, 8)
        val statement = StatementBuilder.day(emptyList(), day, "INVEST", zone)
        assertEquals("今日の自己投資明細", statement.title)
        assertEquals(0.0, statement.amount, 0.0)
    }

    @Test fun combinedProjectsAreNotAttributedToTheFirstProject() {
        val day = LocalDate.of(2026, 9, 7)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val records = listOf(
            Record(1, "manual", "実装", "INVEST", start, start + 1000, 1000, 1800.0, "アプリA", "個人開発"),
            Record(2, "manual", "デザイン", "INVEST", start + 1000, start + 3000, 2000, 1800.0, "作品B", "創作"),
        )
        val statement = StatementBuilder.day(records, day, "INVEST", zone)
        assertEquals("", statement.project)
        assertEquals("", statement.category)
        assertEquals(setOf("アプリA", "作品B"), statement.lines.map { it.label }.toSet())
        assertEquals(1.5, statement.amount, 0.0001)
    }
}
