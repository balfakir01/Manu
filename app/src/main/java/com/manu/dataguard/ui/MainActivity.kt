package com.manu.dataguard.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import com.manu.dataguard.data.AppResolver
import com.manu.dataguard.data.AppSummary
import com.manu.dataguard.data.Event
import com.manu.dataguard.data.EventStore
import com.manu.dataguard.data.Rules
import com.manu.dataguard.vpn.GuardVpnService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val REQ_VPN = 1
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: EventStore
    private lateinit var rules: Rules
    private lateinit var apps: AppResolver

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var stats: TextView
    private lateinit var tabApps: Button
    private lateinit var tabLive: Button
    private lateinit var empty: TextView
    private lateinit var adapter: RowAdapter
    private var liveTab = false

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = EventStore.get(this)
        rules = Rules.get(this)
        apps = AppResolver.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), 0)
        }

        status = text("", 18f, bold = true)
        root.addView(status)
        stats = text("", 13f).apply { alpha = 0.75f; setPadding(0, dp(4), 0, dp(8)) }
        root.addView(stats)

        toggle = Button(this).apply { setOnClickListener { onToggle() } }
        root.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val trackerSwitch = Switch(this).apply {
            text = "Block known trackers & ad networks in all apps"
            isChecked = rules.blockTrackers
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, on -> rules.blockTrackers = on }
        }
        root.addView(trackerSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabApps = Button(this).apply { text = "Apps"; setOnClickListener { selectTab(false) } }
        tabLive = Button(this).apply { text = "Live feed"; setOnClickListener { selectTab(true) } }
        tabs.addView(tabApps, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabs.addView(tabLive, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(tabs)

        empty = text("", 14f).apply { alpha = 0.7f; setPadding(0, dp(24), 0, 0) }
        root.addView(empty)

        adapter = RowAdapter(this)
        val list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> onRowClick(this@MainActivity.adapter.rows[pos]) }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        selectTab(false)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onResume() {
        super.onResume()
        main.post(ticker)
    }

    override fun onPause() {
        main.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun selectTab(live: Boolean) {
        liveTab = live
        tabApps.alpha = if (live) 0.5f else 1f
        tabLive.alpha = if (live) 1f else 0.5f
        adapter.rows = emptyList()
        refresh()
    }

    private fun onToggle() {
        if (GuardVpnService.isRunning) {
            startService(Intent(this, GuardVpnService::class.java).setAction(GuardVpnService.ACTION_STOP))
            main.postDelayed({ refresh() }, 300)
        } else {
            val consent = VpnService.prepare(this)
            if (consent != null) startActivityForResult(consent, REQ_VPN) else onActivityResult(REQ_VPN, RESULT_OK, null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) {
            startForegroundService(Intent(this, GuardVpnService::class.java).setAction(GuardVpnService.ACTION_START))
            main.postDelayed({ refresh() }, 500)
        }
    }

    private fun refresh() {
        val on = GuardVpnService.isRunning
        status.text = if (on) "● Watching your apps" else "○ Not watching"
        status.setTextColor(if (on) GREEN else RED)
        toggle.text = if (on) "Stop" else "Start watching"

        val live = liveTab
        io.execute {
            val dayAgo = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            val s = store.stats(dayAgo)
            val rows = if (live) store.recent(200).map { liveRow(it) } else store.apps().map { appRow(it) }
            main.post {
                if (isFinishing || live != liveTab) return@post
                stats.text = "Last 24 h: ${s.lookups} lookups by ${s.apps} apps · " +
                    "${s.toTrackers} to known trackers · ${s.blocked} blocked"
                adapter.rows = rows
                empty.text = if (rows.isNotEmpty()) "" else if (on)
                    "Waiting for apps to go online… open a few apps to see them here."
                else
                    "Tap “Start watching”. Android will ask you to allow a VPN — this stays on your phone; nothing is sent to us or anyone else."
                empty.visibility = if (rows.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }

    private fun appRow(a: AppSummary): Row {
        val cut = rules.isAppBlocked(a.pkg)
        val companies = if (a.companies.isEmpty()) "" else "\nTrackers: " +
            a.companies.take(4).joinToString(", ") + if (a.companies.size > 4) " +${a.companies.size - 4} more" else ""
        val (badge, color) = when {
            cut -> "INTERNET BLOCKED" to RED
            a.trackerDomains > 0 -> "${a.trackerDomains} tracker${if (a.trackerDomains > 1) "s" else ""}" to ORANGE
            else -> null to GREEN
        }
        return Row(
            title = a.label,
            subtitle = "${a.domains} destinations · ${a.blocked} blocked · ${ago(a.lastSeen)}$companies",
            badge = badge,
            badgeColor = color,
            icon = apps.icon(a.pkg),
            key = a,
        )
    }

    private fun liveRow(e: Event): Row {
        val (badge, color) = when {
            e.blocked -> "BLOCKED" to RED
            e.tracker != null -> "TRACKER" to ORANGE
            else -> null to GREEN
        }
        val extra = listOfNotNull(e.tracker, e.reason?.takeIf { e.blocked }).joinToString(" · ")
        return Row(
            title = e.domain,
            subtitle = "${e.label} · ${ago(e.ts)}" + if (extra.isNotEmpty()) "\n$extra" else "",
            badge = badge,
            badgeColor = color,
            icon = apps.icon(e.pkg),
            key = e,
        )
    }

    private fun onRowClick(r: Row) {
        when (val k = r.key) {
            is AppSummary -> startActivity(
                Intent(this, AppDetailActivity::class.java)
                    .putExtra(AppDetailActivity.EXTRA_PKG, k.pkg)
                    .putExtra(AppDetailActivity.EXTRA_LABEL, k.label)
            )
            is Event -> RuleDialog.show(this, k.pkg, k.label, k.domain) { refresh() }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "How this works")
        menu.add(0, 2, 0, "Clear history")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> AlertDialog.Builder(this)
                .setTitle("How Data Guard works")
                .setMessage(HELP)
                .setPositiveButton("OK", null)
                .show()
            2 -> AlertDialog.Builder(this)
                .setMessage("Delete all recorded history? Your block rules are kept.")
                .setPositiveButton("Delete") { _, _ -> io.execute { store.clear(); main.post { refresh() } } }
                .setNegativeButton("Cancel", null)
                .show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
}

private const val HELP = """Before an app can send anything to a company's server, it has to look up that server's address (e.g. "app-measurement.com"). Data Guard sets up a VPN that exists only on your phone and answers those lookups itself, so it can see which app is contacting which company — and refuse to answer for anything you block. Your actual traffic is not routed through any outside server.

Orange = a known tracker, ad network, data broker or fingerprinting company.
Red = blocked.

Tap an app to see every destination it contacted and to cut off its internet entirely. Tap any destination to block or allow it.

Limits, honestly:
• It shows WHO an app talks to, not WHAT it sends — the contents are encrypted.
• An app that uses a hard-coded address or its own encrypted DNS can slip past.
• Blocking takes effect on the app's next lookup; restart the app to be sure.
• Set Settings › Network › Private DNS to Off, or Android may bypass the lookups.
• Only one VPN can run at a time on Android."""
