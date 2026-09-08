package com.example.timecostview

import com.example.timecostview.domain.Record
import com.example.timecostview.domain.RecordRepository
import com.example.timecostview.domain.TrackingSession
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TrackingSessionTest {
    private class MemoryRecords : RecordRepository {
        val rows = linkedMapOf<Long, Record>()
        var completed = emptyList<Record>()
        var failCompletion = false
        var queriedId = -1L
        override fun save(record: Record): Long = (rows.size + 1L).also { rows[it] = record.copy(id = it) }
        override fun update(id: Long, end: Long, duration: Long) {
            rows[id] = rows.getValue(id).copy(end = end, duration = duration)
        }
        override fun today(app: String, now: Long, excludeId: Long): Double {
            queriedId = excludeId
            return 42.0
        }
        override fun complete(parts: List<Record>) {
            check(!failCompletion) { "Simulated write failure" }
            completed = parts
            val first = parts.first()
            update(first.id, first.end, first.duration)
            parts.drop(1).forEach { save(it) }
        }
    }

    private val records = MemoryRecords()
    private val session = TrackingSession(records) { ZoneId.of("UTC") }
    private fun sample(start: Long = 1_000L) = Record(
        0, "manual", "Design", "INVEST", start, start, 0, 1800.0,
        "MoshiDopa", "Development", "Keep metadata",
    )

    @Test fun monotonicTimeDrivesCostDespiteWallClockChanges() {
        session.begin(sample(), 100L)
        assertEquals(42.0, session.todayBase, 0.0)
        assertEquals(session.active!!.id, records.queriedId)
        session.checkpoint(9_000L, 1_100L)
        assertEquals(1_000L, records.rows.getValue(1).duration)
        assertEquals(1_000L, session.elapsed(1_100L))
        val result = session.finish(500L, 2_100L, manual = true)!!
        assertEquals(2_000L, result.duration)
        assertEquals(1_000L, result.end)
        assertEquals(1.0, result.amount, 0.0)
        assertEquals("Keep metadata", result.note)
        assertNull(session.active)
    }

    @Test fun automaticCompletionSplitsMidnightAndPreservesRateAndMetadata() {
        val start = Instant.parse("2026-09-08T23:59:59Z").toEpochMilli()
        session.begin(sample(start).copy(app = "video"), 100L)
        val result = session.finish(start + 3_000, 3_100, manual = false)!!
        assertEquals(listOf(1_000L, 2_000L), records.completed.map { it.duration })
        assertEquals(listOf(1L, 0L), records.completed.map { it.id })
        assertEquals(result.amount, records.completed.sumOf { it.amount }, 0.0)
        assertTrue(records.completed.all { it.rate == result.rate && it.project == result.project && it.note == result.note })
    }

    @Test fun manualCompletionKeepsOneActivityAcrossMidnight() {
        val start = Instant.parse("2026-09-08T23:59:59Z").toEpochMilli()
        session.begin(sample(start), 0L)
        val result = session.finish(start + 10_000L, 10_000L, manual = true)
        assertEquals(listOf(result), records.completed)
    }

    @Test fun finishIsIdempotentAndIdleCheckpointDoesNothing() {
        session.checkpoint(10L, 10L)
        assertTrue(records.rows.isEmpty())
        assertNull(session.finish(10L, 10L, manual = false))
        session.begin(sample(), 0L)
        session.finish(2_000L, 1_000L, manual = true)
        assertNull(session.finish(3_000L, 2_000L, manual = true))
        assertEquals(1, records.rows.size)
        assertEquals(1_000L, records.rows.getValue(1).duration)
        assertEquals(0L, session.elapsed(2_000L))
    }

    @Test fun secondBeginCannotOverwriteActiveSession() {
        session.begin(sample(), 100L)
        val failure = runCatching { session.begin(sample(), 200L) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(1, records.rows.size)
        assertEquals(100L, session.elapsed(200L))
    }

    @Test fun failedCompletionCanBeRetriedWithoutLosingActiveRecord() {
        session.begin(sample(), 0L)
        records.failCompletion = true
        assertTrue(runCatching { session.finish(2_000L, 1_000L, true) }.isFailure)
        assertNotNull(session.active)
        records.failCompletion = false
        session.finish(2_000L, 1_000L, true)
        assertNull(session.active)
        assertEquals(1, records.rows.size)
    }

    @Test fun negativeElapsedIsClamped() {
        session.begin(sample(), 1_000L)
        assertEquals(0L, session.elapsed(999L))
        assertEquals(0L, session.finish(1_000L, 999L, true)!!.duration)
    }
}
