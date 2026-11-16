package com.manu.dataguard.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Process
import java.util.concurrent.ConcurrentHashMap

class AppInfo(val pkg: String, val label: String)

/** Turns the numeric Linux user ID behind a network request into an app name. */
class AppResolver private constructor(private val ctx: Context) {

    companion object {
        @Volatile private var instance: AppResolver? = null
        fun get(ctx: Context): AppResolver =
            instance ?: synchronized(this) { instance ?: AppResolver(ctx.applicationContext).also { instance = it } }

        const val UNKNOWN = "unknown"
    }

    private val pm = ctx.packageManager
    private val byUid = ConcurrentHashMap<Int, AppInfo>()
    private val icons = ConcurrentHashMap<String, Drawable>()

    fun resolve(uid: Int): AppInfo {
        if (uid < 0) return AppInfo(UNKNOWN, "Unidentified app")
        byUid[uid]?.let { return it }
        val appId = uid % 100_000
        val info = when {
            appId == 0 -> AppInfo("root", "Android system (root)")
            appId == Process.SYSTEM_UID -> AppInfo("android", "Android system")
            else -> {
                val pkgs = pm.getPackagesForUid(uid)?.sorted().orEmpty()
                if (pkgs.isEmpty()) {
                    AppInfo("uid:$uid", "System service #$uid")
                } else {
                    val main = pkgs.first()
                    val extra = if (pkgs.size > 1) " (+${pkgs.size - 1} sharing)" else ""
                    AppInfo(main, label(main) + extra)
                }
            }
        }
        byUid[uid] = info
        return info
    }

    fun label(pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    fun icon(pkg: String): Drawable? {
        icons[pkg]?.let { return it }
        return try {
            pm.getApplicationIcon(pkg).also { icons[pkg] = it }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
