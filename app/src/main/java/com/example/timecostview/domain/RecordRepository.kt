package com.example.timecostview.domain

/** Persistence boundary used by the session lifecycle; implementations own atomic writes. */
interface RecordRepository {
    fun save(record: Record): Long
    fun update(id: Long, end: Long, duration: Long)
    fun today(app: String, now: Long = System.currentTimeMillis(), excludeId: Long = -1): Double

    /** Replace the active row with the first part and insert the remaining parts atomically. */
    fun complete(parts: List<Record>)
}
