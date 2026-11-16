package com.manu.dataguard.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.atomic.AtomicInteger

class Event(
    val ts: Long,
    val pkg: String,
    val label: String,
    val domain: String,
    val tracker: String?,
    val blocked: Boolean,
    val reason: String?,
)

class AppSummary(
    val pkg: String,
    val label: String,
    val lookups: Int,
    val domains: Int,
    val trackerDomains: Int,
    val blocked: Int,
    val lastSeen: Long,
    val companies: List<String>,
)

class DomainSummary(
    val domain: String,
    val tracker: String?,
    val lookups: Int,
    val blocked: Int,
    val lastSeen: Long,
)

class Stats(val lookups: Int, val toTrackers: Int, val blocked: Int, val apps: Int)

/** On-device history of every lookup (kept 7 days). Nothing here ever leaves the phone. */
class EventStore private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx, "events.db", null, 1) {

    companion object {
        @Volatile private var instance: EventStore? = null
        fun get(ctx: Context): EventStore =
            instance ?: synchronized(this) { instance ?: EventStore(ctx.applicationContext).also { instance = it } }

        private const val KEEP_MS = 7L * 24 * 60 * 60 * 1000
    }

    private val inserts = AtomicInteger()

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                pkg TEXT NOT NULL,
                label TEXT NOT NULL,
                domain TEXT NOT NULL,
                tracker TEXT,
                blocked INTEGER NOT NULL,
                reason TEXT)"""
        )
        db.execSQL("CREATE INDEX idx_ts ON events(ts)")
        db.execSQL("CREATE INDEX idx_pkg_domain ON events(pkg, domain)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun record(e: Event) {
        val v = ContentValues().apply {
            put("ts", e.ts)
            put("pkg", e.pkg)
            put("label", e.label)
            put("domain", e.domain)
            put("tracker", e.tracker)
            put("blocked", if (e.blocked) 1 else 0)
            put("reason", e.reason)
        }
        writableDatabase.insert("events", null, v)
        if (inserts.incrementAndGet() % 500 == 0) {
            writableDatabase.delete("events", "ts < ?", arrayOf((System.currentTimeMillis() - KEEP_MS).toString()))
        }
    }

    fun recent(limit: Int = 200): List<Event> {
        val out = mutableListOf<Event>()
        readableDatabase.rawQuery(
            "SELECT ts, pkg, label, domain, tracker, blocked, reason FROM events ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += Event(
                    c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                    c.getStringOrNull(4), c.getInt(5) == 1, c.getStringOrNull(6)
                )
            }
        }
        return out
    }

    fun apps(): List<AppSummary> {
        val out = mutableListOf<AppSummary>()
        readableDatabase.rawQuery(
            """SELECT pkg, MAX(label), COUNT(*), COUNT(DISTINCT domain),
                      COUNT(DISTINCT CASE WHEN tracker IS NOT NULL THEN domain END),
                      SUM(blocked), MAX(ts), GROUP_CONCAT(DISTINCT tracker)
               FROM events GROUP BY pkg ORDER BY MAX(ts) DESC""",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val companies = (c.getStringOrNull(7) ?: "")
                    .split(',').map { it.substringBefore(" · ").trim() }.filter { it.isNotEmpty() }.distinct()
                out += AppSummary(
                    c.getString(0), c.getString(1), c.getInt(2), c.getInt(3),
                    c.getInt(4), c.getInt(5), c.getLong(6), companies
                )
            }
        }
        return out
    }

    fun domainsFor(pkg: String): List<DomainSummary> {
        val out = mutableListOf<DomainSummary>()
        readableDatabase.rawQuery(
            """SELECT domain, MAX(tracker), COUNT(*), SUM(blocked), MAX(ts)
               FROM events WHERE pkg = ? GROUP BY domain
               ORDER BY (MAX(tracker) IS NULL), MAX(ts) DESC""",
            arrayOf(pkg)
        ).use { c ->
            while (c.moveToNext()) {
                out += DomainSummary(c.getString(0), c.getStringOrNull(1), c.getInt(2), c.getInt(3), c.getLong(4))
            }
        }
        return out
    }

    fun stats(sinceMs: Long): Stats {
        readableDatabase.rawQuery(
            """SELECT COUNT(*), SUM(tracker IS NOT NULL), SUM(blocked), COUNT(DISTINCT pkg)
               FROM events WHERE ts >= ?""",
            arrayOf(sinceMs.toString())
        ).use { c ->
            c.moveToFirst()
            return Stats(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3))
        }
    }

    fun clear() {
        writableDatabase.delete("events", null, null)
    }

    private fun android.database.Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
}
