package com.example.timecostview.domain

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class StatementPeriod { SESSION, DAY, MONTH }

data class StatementLine(
    val label: String,
    val amount: Double,
    val duration: Long,
    val project: String = "",
    val category: String = "",
    val notes: List<String> = emptyList(),
) : java.io.Serializable

data class Statement(
    val mode: String,
    val period: StatementPeriod,
    val title: String,
    val periodLabel: String,
    val rangeLabel: String,
    val measuredLabel: String,
    val start: Long,
    val end: Long,
    val duration: Long,
    val amount: Double,
    val lines: List<StatementLine>,
    val project: String = "",
    val category: String = "",
    val notes: List<String> = emptyList(),
    val isPartial: Boolean = false,
) : java.io.Serializable {
    val spend get() = mode != "INVEST"
}

/** Builds the same statement model for the on-screen preview and the share card. */
object StatementBuilder {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm", Locale.JAPAN)
    private val dayFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.JAPAN)
    private val monthFormatter = DateTimeFormatter.ofPattern("yyyy年M月", Locale.JAPAN)

    fun fromRecord(record: Record, zone: ZoneId = ZoneId.systemDefault()): Statement {
        val day = Instant.ofEpochMilli(record.start).atZone(zone).toLocalDate()
        val label = if(record.mode == "INVEST") "自己投資明細" else "もしも給与明細"
        return Statement(
            mode = record.mode,
            period = StatementPeriod.SESSION,
            title = label,
            periodLabel = dateFormatter.format(Instant.ofEpochMilli(record.start).atZone(zone)),
            rangeLabel = "${dayFormatter.format(day.atStartOfDay(zone))} · ${record.title}",
            measuredLabel = "実測範囲 ${dateFormatter.format(Instant.ofEpochMilli(record.start).atZone(zone))}〜${dateFormatter.format(Instant.ofEpochMilli(record.end).atZone(zone))}",
            start = record.start,
            end = record.end,
            duration = record.duration,
            amount = record.amount,
            lines = listOf(StatementLine(record.title, record.amount, record.duration, record.project, record.category, listOfNotBlank(record.note))),
            project = record.project,
            category = record.category,
            notes = listOfNotBlank(record.note),
        )
    }

    fun day(records: List<Record>, day: LocalDate, mode: String, zone: ZoneId = ZoneId.systemDefault()): Statement =
        build(records.filter { it.mode == mode }, StatementPeriod.DAY, day, null, zone, mode)

    fun month(records: List<Record>, month: YearMonth, mode: String, zone: ZoneId = ZoneId.systemDefault()): Statement =
        build(records.filter { it.mode == mode }, StatementPeriod.MONTH, null, month, zone, mode)

    fun group(records: List<Record>, period: StatementPeriod, mode: String, zone: ZoneId = ZoneId.systemDefault()): Statement {
        val first = records.minByOrNull { it.start }
        val day = first?.let { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }
        val month = first?.let { YearMonth.from(Instant.ofEpochMilli(it.start).atZone(zone)) }
        val base = when(period) {
            StatementPeriod.MONTH -> build(records.filter { it.mode == mode }, period, null, month, zone, mode)
            else -> build(records.filter { it.mode == mode }, StatementPeriod.DAY, day, null, zone, mode)
        }
        return base.copy(
            title = base.title,
            project = records.firstOrNull { it.project.isNotBlank() }?.project.orEmpty(),
            category = records.firstOrNull { it.category.isNotBlank() }?.category.orEmpty(),
            notes = records.flatMap { listOfNotBlank(it.note) }.distinct(),
            lines = groupsToLines(records),
        )
    }

    private fun build(
        allRecords: List<Record>,
        period: StatementPeriod,
        day: LocalDate?,
        month: YearMonth?,
        zone: ZoneId,
        requestedMode: String? = null,
    ): Statement {
        val periodRecords = when(period) {
            StatementPeriod.DAY -> HistorySummary.days(allRecords, zone)[day].orEmpty()
            StatementPeriod.MONTH -> HistorySummary.months(allRecords, zone)[month].orEmpty()
            StatementPeriod.SESSION -> emptyList()
        }
        val start = when(period) {
            StatementPeriod.DAY -> requireNotNull(day).atStartOfDay(zone).toInstant().toEpochMilli()
            StatementPeriod.MONTH -> requireNotNull(month).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            StatementPeriod.SESSION -> periodRecords.minOfOrNull { it.start } ?: 0L
        }
        val boundary = when(period) {
            StatementPeriod.DAY -> requireNotNull(day).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            StatementPeriod.MONTH -> requireNotNull(month).plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            StatementPeriod.SESSION -> periodRecords.maxOfOrNull { it.end } ?: start
        }
        val now = System.currentTimeMillis()
        val current = when(period) {
            StatementPeriod.DAY -> day == LocalDate.now(zone)
            StatementPeriod.MONTH -> month == YearMonth.now(zone)
            StatementPeriod.SESSION -> false
        }
        val measured = if(periodRecords.isEmpty()) "計測記録なし" else {
            val first = periodRecords.minOf { it.start }
            val last = periodRecords.maxOf { it.end }
            "実測範囲 ${dateFormatter.format(Instant.ofEpochMilli(first).atZone(zone))}〜${dateFormatter.format(Instant.ofEpochMilli(last).atZone(zone))}"
        }
        val mode = requestedMode ?: allRecords.firstOrNull()?.mode ?: "SPEND"
        val title = when(mode to period) {
            "SPEND" to StatementPeriod.DAY -> "もしも日給明細"
            "SPEND" to StatementPeriod.MONTH -> "もしも月給明細"
            "INVEST" to StatementPeriod.DAY -> "今日の自己投資明細"
            "INVEST" to StatementPeriod.MONTH -> "今月の自己投資明細"
            else -> if(mode == "INVEST") "自己投資明細" else "もしも給与明細"
        }
        val periodLabel = when(period) {
            StatementPeriod.DAY -> dayFormatter.format(requireNotNull(day).atStartOfDay(zone))
            StatementPeriod.MONTH -> monthFormatter.format(requireNotNull(month).atDay(1).atStartOfDay(zone))
            StatementPeriod.SESSION -> ""
        }
        val range = when {
            period == StatementPeriod.DAY && current -> "$periodLabel（途中集計）"
            period == StatementPeriod.MONTH && current -> {
                val today = LocalDate.now(zone)
                "${monthFormatter.format(requireNotNull(month).atDay(1).atStartOfDay(zone))} 1日〜${today.dayOfMonth}日（途中集計）"
            }
            period == StatementPeriod.MONTH -> {
                val m = requireNotNull(month)
                "${m.year}年${m.monthValue}月1日〜${m.lengthOfMonth()}日"
            }
            else -> periodLabel
        }
        return Statement(
            mode = mode,
            period = period,
            title = title,
            periodLabel = periodLabel,
            rangeLabel = range,
            measuredLabel = measured,
            start = start,
            end = if(current) now else boundary,
            duration = periodRecords.sumOf { it.duration },
            amount = periodRecords.sumOf { it.amount },
            lines = groupsToLines(periodRecords),
            project = periodRecords.map { it.project }.distinct().singleOrNull().orEmpty(),
            category = periodRecords.map { it.category }.distinct().singleOrNull().orEmpty(),
            notes = periodRecords.flatMap { listOfNotBlank(it.note) }.distinct(),
            isPartial = current,
        )
    }

    private fun groupsToLines(records: List<Record>): List<StatementLine> = HistorySummary.groups(records).map { group ->
        StatementLine(
            label = if(group.mode == "INVEST") group.displayName else group.title,
            amount = group.amount,
            duration = group.duration,
            project = group.project,
            category = group.category,
            notes = group.notes,
        )
    }

    private fun listOfNotBlank(value: String): List<String> = value.trim().takeIf { it.isNotBlank() }?.let(::listOf) ?: emptyList()
}
