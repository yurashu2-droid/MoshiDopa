package com.example.timecostview.domain

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Partition elapsed time into local calendar days without dropping fractional seconds. */
fun splitAtMidnight(record: Record, zone: ZoneId = ZoneId.systemDefault()): List<Record> {
    if(record.duration <= 0) return listOf(record)
    val end = record.start + record.duration
    var cursor = record.start
    return buildList {
        while(cursor < end) {
            val midnight = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate().plusDays(1)
                .atStartOfDay(zone).toInstant().toEpochMilli()
            val until = minOf(midnight, end)
            add(record.copy(id = if(cursor == record.start) record.id else 0, start = cursor, end = until, duration = until - cursor))
            cursor = until
        }
    }
}

/** Partition elapsed time into local calendar months without changing the saved rate. */
fun splitAtMonth(record: Record, zone: ZoneId = ZoneId.systemDefault()): List<Record> {
    if(record.duration <= 0) return listOf(record)
    val end = record.start + record.duration
    var cursor = record.start
    return buildList {
        while(cursor < end) {
            val local = Instant.ofEpochMilli(cursor).atZone(zone)
            val nextMonth = YearMonth.from(local).plusMonths(1).atDay(1)
                .atStartOfDay(zone).toInstant().toEpochMilli()
            val until = minOf(nextMonth, end)
            add(record.copy(
                id = if(cursor == record.start) record.id else 0,
                start = cursor,
                end = until,
                duration = until - cursor,
            ))
            cursor = until
        }
    }
}
