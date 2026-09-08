package com.example.timecostview.domain

import java.time.ZoneId

/**
 * One session, driven by explicit wall/monotonic timestamps from the platform.
 * Android scheduling, permissions and overlay effects belong to the caller.
 * Call on the service thread. A failed save leaves the active session available for retry.
 */
class TrackingSession(
    private val records: RecordRepository,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    var active: Record? = null
        private set
    var todayBase: Double = 0.0
        private set
    private var startMono = 0L

    fun begin(record: Record, mono: Long) {
        check(active == null) { "Finish the active session before starting another" }
        val saved = record.copy(id = records.save(record))
        active = saved
        startMono = mono
        todayBase = records.today(saved.app, saved.start, saved.id)
    }

    fun elapsed(mono: Long): Long = if(active == null) 0L else (mono - startMono).coerceAtLeast(0)

    fun checkpoint(wall: Long, mono: Long) {
        val record = active ?: return
        records.update(record.id, wall, elapsed(mono))
    }

    fun finish(wall: Long, mono: Long, manual: Boolean): Record? {
        val record = active ?: return null
        val result = record.copy(end = wall.coerceAtLeast(record.start), duration = elapsed(mono))
        records.complete(if(manual) listOf(result) else splitAtMidnight(result, zone()))
        active = null
        return result
    }
}
