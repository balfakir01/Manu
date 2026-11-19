package com.manu.dataguard.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import com.manu.dataguard.data.AppResolver
import com.manu.dataguard.data.DomainSummary
import com.manu.dataguard.data.EventStore
import com.manu.dataguard.data.Rules
import com.manu.dataguard.data.Trackers
import java.util.concurrent.Executors

/** Every destination one app has contacted, with controls to block it. */
class AppDetailActivity : Activity() {

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_LABEL = "label"
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var pkg: String
    private lateinit var label: String
    private lateinit var rules: Rules
    private lateinit var store: EventStore
    private lateinit var summary: TextView
    private lateinit var adapter: RowAdapter

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent.getStringExtra(EXTRA_PKG) ?: run { finish(); return }
        label = intent.getStringExtra(EXTRA_LABEL) ?: pkg
        title = label
        rules = Rules.get(this)
        store = EventStore.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), 0)
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        AppResolver.get(this).icon(pkg)?.let { d ->
            header.addView(ImageView(this).apply { setImageDrawable(d) }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
        }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(text(label, 20f, bold = true))
        names.addView(text(pkg, 12f).apply { alpha = 0.6f })
        header.addView(names)
        root.addView(header)

        val identifiable = pkg != AppResolver.UNKNOWN
        val cut = Switch(this).apply {
            text = "Block ALL internet for this app"
            isChecked = rules.isAppBlocked(pkg)
            isEnabled = identifiable
            setPadding(0, dp(16), 0, dp(4))
            setOnCheckedChangeListener { _, on -> rules.setAppBlocked(pkg, on); refresh() }
        }
        root.addView(cut, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(text("Restart the app afterwards so it can't reuse addresses it already looked up.", 12f).apply { alpha = 0.6f })

        root.addView(android.widget.Button(this).apply {
            text = "Show where this app sends data on a map"
            setOnClickListener {
                startActivity(
                    android.content.Intent(this@AppDetailActivity, MapActivity::class.java)
                        .putExtra(MapActivity.EXTRA_PKG, pkg)
                        .putExtra(MapActivity.EXTRA_LABEL, label)
                )
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        summary = text("", 13f).apply { setPadding(0, dp(12), 0, dp(4)); alpha = 0.8f }
        root.addView(summary)
        root.addView(text("Tap a destination to block or allow it.", 12f).apply { alpha = 0.6f })

        adapter = RowAdapter(this)
        val list = ListView(this).apply {
            adapter = this@AppDetailActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                val d = this@AppDetailActivity.adapter.rows[pos].key as DomainSummary
                RuleDialog.show(this@AppDetailActivity, pkg, label, d.domain) { refresh() }
            }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
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

    private fun refresh() {
        io.execute {
            val domains = store.domainsFor(pkg)
            val rows = domains.map { row(it) }
            val trackers = domains.count { it.tracker != null }
            val companies = domains.mapNotNull { it.tracker?.substringBefore(" · ") }.distinct()
            main.post {
                if (isFinishing) return@post
                summary.text = "${domains.size} destinations contacted, $trackers belong to known trackers" +
                    if (companies.isEmpty()) "." else ":\n" + companies.joinToString(", ")
                adapter.rows = rows
            }
        }
    }

    private fun row(d: DomainSummary): Row {
        val decision = rules.decide(pkg, d.domain, Trackers.lookup(d.domain) != null)
        val (badge, color) = when {
            decision.blocked -> "BLOCKED" to RED
            d.tracker != null -> "TRACKER" to ORANGE
            else -> "allowed" to GREEN
        }
        val lines = mutableListOf("${d.lookups} lookups · last ${ago(d.lastSeen)}" + if (d.blocked > 0) " · ${d.blocked} blocked" else "")
        d.tracker?.let { lines += it }
        decision.reason?.let { lines += it }
        return Row(d.domain, lines.joinToString("\n"), badge, color, null, d)
    }
}
