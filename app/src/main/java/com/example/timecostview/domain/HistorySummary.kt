package com.example.timecostview.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth

data class HistoryGroup(val key: String, val records: List<Record>) {
    val amount get() = records.sumOf { it.amount }
    val duration get() = records.sumOf { it.duration }
    val title get() = records.first().title
    val mode get() = records.first().mode
    val project get() = records.firstOrNull { it.project.isNotBlank() }?.project.orEmpty()
    val category get() = records.firstOrNull { it.category.isNotBlank() }?.category.orEmpty()
    val notes get() = records.map { it.note.trim() }.filter { it.isNotBlank() }.distinct()
    val displayName get() = project.ifBlank { title }
    /** A share-only aggregate, never written back to the record database. */
    fun receipt(): Record {
        val first = records.minBy { it.start }
        return first.copy(id = -first.id, title = "$title / 1日分",
            end = records.maxOf { it.end }, duration = duration,
            rate = if(duration > 0) amount * 3_600_000.0 / duration else first.rate)
    }
}

object HistorySummary {
    /** Split for display only, retaining source IDs for opening the original receipt. */
    fun days(records: List<Record>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, List<Record>> =
        records.filter { it.duration > 0 }.flatMap { source ->
            splitAtMidnight(source, zone).map { it.copy(id = source.id) }
        }.groupBy { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }

    /** Split a record at month boundaries for reliable calendar-month totals. */
    fun months(records: List<Record>, zone: ZoneId = ZoneId.systemDefault()): Map<YearMonth, List<Record>> =
        records.filter { it.duration > 0 }.flatMap { source ->
            splitAtMonth(source, zone).map { it.copy(id = source.id) }
        }.groupBy { YearMonth.from(Instant.ofEpochMilli(it.start).atZone(zone)) }

    fun groups(records: List<Record>): List<HistoryGroup> = records.groupBy {
        // Manual activities and INVEST projects must remain separately addressable.
        if(it.app == "manual" || it.mode == "INVEST") {
            "${it.app}|${it.mode}|${it.title}|${it.project}|${it.category}"
        } else {
            // Automatic SPEND sessions are grouped by source app even if its
            // visible label changed between Android sessions.
            "${it.app}|${it.mode}"
        }
    }.map { (key, values) -> HistoryGroup(key, values.sortedByDescending { it.start }) }
        .sortedByDescending { it.amount }
}
