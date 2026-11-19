package com.manu.dataguard.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.manu.dataguard.data.CountrySummary
import com.manu.dataguard.data.EventStore
import com.manu.dataguard.data.Geo
import com.manu.dataguard.data.World
import java.util.concurrent.Executors

/** Where your data went: a world map plus a per-country breakdown. Optionally for one app. */
class MapActivity : Activity() {

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_LABEL = "label"
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: EventStore
    private var pkg: String? = null
    private var home: String? = null
    private lateinit var adapter: RowAdapter
    private lateinit var summary: TextView
    private var map: WorldMapView? = null
    private lateinit var header: LinearLayout

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = EventStore.get(this)
        pkg = intent.getStringExtra(EXTRA_PKG)
        title = intent.getStringExtra(EXTRA_LABEL)?.let { "Where $it sends data" } ?: "Where your data goes"
        home = Geo.homeCountry(this)

        header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        summary = text("Loading map…", 14f).apply { setPadding(0, dp(4), 0, dp(8)) }
        header.addView(summary)

        adapter = RowAdapter(this)
        val list = ListView(this).apply {
            addHeaderView(header, null, false)
            adapter = this@MapActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                val row = this@MapActivity.adapter.rows.getOrNull(pos - headerViewsCount) ?: return@setOnItemClickListener
                showCountry((row.key as CountrySummary).country)
            }
            setPadding(dp(16), 0, dp(16), 0)
            clipToPadding = false
        }
        setContentView(list)

        // The map files take a moment to load, so build the map off the main thread.
        io.execute {
            val world = World.get(this)
            main.post {
                if (isFinishing) return@post
                val view = WorldMapView(this, world).apply {
                    home = this@MapActivity.home
                    onCountryTapped = { cc -> showCountry(cc) }
                }
                map = view
                header.addView(view, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                header.addView(legend(), 1)
                header.addView(
                    text(
                        "Countries show where each server is registered. Big companies use data centres " +
                            "worldwide, so a server near you can still belong to a company abroad.\n" +
                            "IP geolocation by DB-IP.com (CC BY 4.0) · Map: Natural Earth",
                        11f
                    ).apply { alpha = 0.6f; setPadding(0, dp(4), 0, dp(4)) }
                )
                refresh()
            }
        }
    }

    private fun legend(): TextView = text(
        "● You (${home?.let { Geo.countryName(it) } ?: "unknown"})   ● Trackers involved   ● Other servers",
        12f
    ).apply {
        val s = android.text.SpannableString(text)
        var idx = 0
        for (color in listOf(GREEN, RED, WorldMapView.BLUE)) {
            idx = s.indexOf('●', idx)
            s.setSpan(android.text.style.ForegroundColorSpan(color), idx, idx + 1, 0)
            idx++
        }
        text = s
        setPadding(0, dp(6), 0, 0)
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
        val p = pkg
        io.execute {
            val countries = store.countries(p)
            val blocked = store.blockedCount(p)
            main.post {
                if (isFinishing) return@post
                map?.data = countries
                val abroad = countries.filter { it.country != home }
                val abroadShare = if (countries.isEmpty()) 0 else
                    (100 * abroad.sumOf { it.lookups } / countries.sumOf { it.lookups })
                summary.text = if (countries.isEmpty()) {
                    "No destinations located yet. Turn on watching and use a few apps — servers appear here as they're contacted."
                } else buildString {
                    append("Data went to servers in ${countries.size} ${if (countries.size == 1) "country" else "countries"}")
                    if (home != null) append("; $abroadShare% of connections left ${Geo.countryName(home!!)}")
                    append(".")
                    if (blocked > 0) append("\n$blocked blocked lookups never left your phone.")
                }
                adapter.rows = countries.map { row(it) }
            }
        }
    }

    private fun row(s: CountrySummary): Row {
        val name = Geo.countryName(s.country)
        val you = if (s.country == home) " (your country)" else ""
        val apps = if (pkg == null) " · ${s.apps} app${if (s.apps == 1) "" else "s"}" else ""
        return Row(
            title = "${Geo.flag(s.country)}  $name$you",
            subtitle = "${s.lookups} connections · ${s.domains} servers$apps",
            badge = if (s.trackerDomains > 0) "${s.trackerDomains} tracker${if (s.trackerDomains == 1) "" else "s"}" else null,
            badgeColor = ORANGE,
            icon = null,
            key = s,
        )
    }

    private fun showCountry(cc: String) {
        map?.selected = cc
        val p = pkg
        io.execute {
            val domains = store.domainsIn(cc, p)
            main.post {
                if (isFinishing) return@post
                val body = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(24), dp(8), dp(24), dp(8))
                }
                for (d in domains) {
                    body.addView(text(d.domain, 14f, bold = true).apply {
                        if (d.tracker != null) setTextColor(ORANGE)
                        setPadding(0, dp(8), 0, 0)
                    })
                    val parts = listOfNotNull(
                        d.tracker,
                        if (pkg == null) "Apps: ${d.apps.replace(",", ", ")}" else null,
                        "${d.lookups} connections" + (d.ip?.let { " · $it" } ?: ""),
                    )
                    body.addView(text(parts.joinToString("\n"), 12f).apply { alpha = 0.75f })
                }
                if (domains.isEmpty()) body.addView(text("Nothing recorded."))
                AlertDialog.Builder(this)
                    .setTitle("${Geo.flag(cc)}  ${Geo.countryName(cc)}")
                    .setView(ScrollView(this).apply { addView(body) })
                    .setPositiveButton("Close", null)
                    .setOnDismissListener { map?.selected = null }
                    .show()
            }
        }
    }
}
