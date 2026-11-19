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
    val ip: String? = null,
    val country: String? = null,
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

class CountrySummary(
    val country: String,
    val lookups: Int,
    val domains: Int,
    val apps: Int,
    val trackerDomains: Int,
)

class CountryDomain(val domain: String, val tracker: String?, val lookups: Int, val apps: String, val ip: String?)

/** On-device history of every lookup (kept 7 days). Nothing here ever leaves the phone. */
class EventStore private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx, "events.db", null, 2) {

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
                reason TEXT,
                ip TEXT,
                country TEXT)"""
        )
        db.execSQL("CREATE INDEX idx_ts ON events(ts)")
        db.execSQL("CREATE INDEX idx_pkg_domain ON events(pkg, domain)")
        db.execSQL("CREATE INDEX idx_country ON events(country)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE events ADD COLUMN ip TEXT")
            db.execSQL("ALTER TABLE events ADD COLUMN country TEXT")
            db.execSQL("CREATE INDEX idx_country ON events(country)")
        }
    }

    fun record(e: Event) {
        val v = ContentValues().apply {
            put("ts", e.ts)
            put("pkg", e.pkg)
            put("label", e.label)
            put("domain", e.domain)
            put("tracker", e.tracker)
            put("blocked", if (e.blocked) 1 else 0)
            put("reason", e.reason)
            put("ip", e.ip)
            put("country", e.country)
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

    /** Where data went, per server country. [pkg] limits it to one app. */
    fun countries(pkg: String? = null): List<CountrySummary> {
        val out = mutableListOf<CountrySummary>()
        val (where, args) = appFilter(pkg)
        readableDatabase.rawQuery(
            """SELECT country, COUNT(*), COUNT(DISTINCT domain), COUNT(DISTINCT pkg),
                      COUNT(DISTINCT CASE WHEN tracker IS NOT NULL THEN domain END)
               FROM events WHERE country IS NOT NULL $where
               GROUP BY country ORDER BY COUNT(*) DESC""",
            args
        ).use { c ->
            while (c.moveToNext()) out += CountrySummary(c.getString(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4))
        }
        return out
    }

    /** Lookups that were blocked, i.e. the app never got an address to send data to. */
    fun blockedCount(pkg: String? = null): Int {
        val (where, args) = appFilter(pkg)
        readableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE blocked = 1 $where", args).use { c ->
            c.moveToFirst()
            return c.getInt(0)
        }
    }

    fun domainsIn(country: String, pkg: String? = null): List<CountryDomain> {
        val out = mutableListOf<CountryDomain>()
        val (where, args) = appFilter(pkg)
        readableDatabase.rawQuery(
            """SELECT domain, MAX(tracker), COUNT(*), GROUP_CONCAT(DISTINCT label), MAX(ip)
               FROM events WHERE country = ? $where
               GROUP BY domain ORDER BY (MAX(tracker) IS NULL), COUNT(*) DESC LIMIT 100""",
            arrayOf(country) + (args ?: emptyArray())
        ).use { c ->
            while (c.moveToNext()) {
                out += CountryDomain(c.getString(0), c.getStringOrNull(1), c.getInt(2), c.getString(3), c.getStringOrNull(4))
            }
        }
        return out
    }

    private fun appFilter(pkg: String?): Pair<String, Array<String>?> =
        if (pkg == null) "" to null else "AND pkg = ?" to arrayOf(pkg)

    fun clear() {
        writableDatabase.delete("events", null, null)
    }

    private fun android.database.Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
}
