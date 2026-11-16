package com.manu.dataguard.data

import android.content.Context

class Decision(val blocked: Boolean, val reason: String?)

/**
 * The user's choices. Order of precedence when an app looks up a domain:
 *  1. App's internet fully blocked          -> block
 *  2. Domain allowed for this app           -> allow (overrides everything below)
 *  3. Domain blocked for this app / for all -> block
 *  4. Domain is a known tracker and the "block trackers" switch is on -> block
 *  5. Otherwise                             -> allow
 * Domain rules also cover subdomains.
 */
class Rules private constructor(ctx: Context) {

    companion object {
        @Volatile private var instance: Rules? = null
        fun get(ctx: Context): Rules =
            instance ?: synchronized(this) { instance ?: Rules(ctx.applicationContext).also { instance = it } }

        private const val KEY_TRACKERS = "block_trackers"
        private const val KEY_APPS = "blocked_apps"
        private const val KEY_GLOBAL = "blocked_everywhere"
        private const val KEY_APP_BLOCK = "blocked_for_app"
        private const val KEY_APP_ALLOW = "allowed_for_app"
    }

    private val prefs = ctx.getSharedPreferences("rules", Context.MODE_PRIVATE)

    var blockTrackers: Boolean
        get() = prefs.getBoolean(KEY_TRACKERS, false)
        set(v) = prefs.edit().putBoolean(KEY_TRACKERS, v).apply()

    private fun set(key: String): Set<String> = prefs.getStringSet(key, emptySet()) ?: emptySet()

    @Synchronized
    private fun edit(key: String, value: String, add: Boolean) {
        val s = set(key).toMutableSet()
        if (add) s += value else s -= value
        prefs.edit().putStringSet(key, s).apply()
    }

    private fun pair(pkg: String, domain: String) = "$pkg|$domain"

    fun isAppBlocked(pkg: String) = pkg in set(KEY_APPS)
    fun setAppBlocked(pkg: String, blocked: Boolean) = edit(KEY_APPS, pkg, blocked)

    fun blockForApp(pkg: String, domain: String) {
        edit(KEY_APP_ALLOW, pair(pkg, domain), false)
        edit(KEY_APP_BLOCK, pair(pkg, domain), true)
    }

    fun blockEverywhere(domain: String) = edit(KEY_GLOBAL, domain, true)

    fun allowForApp(pkg: String, domain: String) {
        edit(KEY_APP_BLOCK, pair(pkg, domain), false)
        edit(KEY_APP_ALLOW, pair(pkg, domain), true)
    }

    fun clearRules(pkg: String, domain: String) {
        edit(KEY_APP_BLOCK, pair(pkg, domain), false)
        edit(KEY_APP_ALLOW, pair(pkg, domain), false)
        edit(KEY_GLOBAL, domain, false)
    }

    fun decide(pkg: String, domain: String, isTracker: Boolean): Decision {
        if (isAppBlocked(pkg)) return Decision(true, "All internet blocked for this app")
        val suffixes = Trackers.suffixes(domain)
        val allowed = set(KEY_APP_ALLOW)
        if (suffixes.any { pair(pkg, it) in allowed }) return Decision(false, "Allowed by you")
        val global = set(KEY_GLOBAL)
        if (suffixes.any { it in global }) return Decision(true, "Blocked in every app")
        val perApp = set(KEY_APP_BLOCK)
        if (suffixes.any { pair(pkg, it) in perApp }) return Decision(true, "Blocked for this app")
        if (isTracker && blockTrackers) return Decision(true, "Known tracker")
        return Decision(false, null)
    }
}
