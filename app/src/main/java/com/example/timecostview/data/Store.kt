package com.example.timecostview.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.timecostview.domain.Record
import java.time.LocalDate
import java.time.ZoneId

class Store(context: Context) : SQLiteOpenHelper(context, "time-cost.db", null, 2) {
    val prefs = context.getSharedPreferences("time-cost", Context.MODE_PRIVATE)
    val rate get() = prefs.getString("rate", "1800")!!.toDouble()
    val targets get() = prefs.getStringSet("targets", emptySet())!!.toSet()
    override fun onCreate(db: SQLiteDatabase) {
        // end は予約語なので `end` とバッククォートで囲んで識別子として明示します
        db.execSQL("CREATE TABLE records(id INTEGER PRIMARY KEY AUTOINCREMENT, app TEXT NOT NULL, title TEXT NOT NULL, mode TEXT NOT NULL, start INTEGER NOT NULL, `end` INTEGER NOT NULL, duration INTEGER NOT NULL, rate REAL NOT NULL, project TEXT NOT NULL DEFAULT '', category TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE INDEX by_start ON records(start)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion < 2) {
            db.execSQL("ALTER TABLE records ADD COLUMN project TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE records ADD COLUMN category TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE records ADD COLUMN note TEXT NOT NULL DEFAULT ''")
        }
    }
    fun save(r: Record): Long = writableDatabase.insertOrThrow("records", null, ContentValues().apply {
        put("app", r.app); put("title", r.title); put("mode", r.mode); put("start", r.start)
        put("end", r.end); put("duration", r.duration); put("rate", r.rate)
        put("project", r.project); put("category", r.category); put("note", r.note)
    })
    fun update(id: Long, end: Long, duration: Long) {
        writableDatabase.update("records", ContentValues().apply { put("end", end); put("duration", duration) }, "id=?", arrayOf(id.toString()))
    }
    fun records(): List<Record> = readableDatabase.query("records", null, null, null, null, null, "start DESC").use { c ->
        val id = c.getColumnIndexOrThrow("id")
        val app = c.getColumnIndexOrThrow("app")
        val title = c.getColumnIndexOrThrow("title")
        val mode = c.getColumnIndexOrThrow("mode")
        val start = c.getColumnIndexOrThrow("start")
        val end = c.getColumnIndexOrThrow("end")
        val duration = c.getColumnIndexOrThrow("duration")
        val rate = c.getColumnIndexOrThrow("rate")
        val project = c.getColumnIndexOrThrow("project")
        val category = c.getColumnIndexOrThrow("category")
        val note = c.getColumnIndexOrThrow("note")
        buildList { while(c.moveToNext()) add(Record(
            c.getLong(id), c.getString(app), c.getString(title), c.getString(mode),
            c.getLong(start), c.getLong(end), c.getLong(duration), c.getDouble(rate),
            c.getString(project), c.getString(category), c.getString(note))) }
    }
    fun today(app: String, now: Long = System.currentTimeMillis(), excludeId: Long = -1): Double {
        val start = java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return readableDatabase.rawQuery("SELECT COALESCE(SUM(duration * rate / 3600000.0),0) FROM records WHERE app=? AND start>=? AND id!=?", arrayOf(app, start.toString(), excludeId.toString())).use { it.moveToFirst(); it.getDouble(0) }
    }
}
