package com.manu.dataguard.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.manu.dataguard.data.Rules
import com.manu.dataguard.data.Trackers

val RED = Color.parseColor("#D32F2F")
val ORANGE = Color.parseColor("#EF6C00")
val GREEN = Color.parseColor("#2E7D32")

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.text(s: String, sizeSp: Float = 15f, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        textSize = sizeSp
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

fun ago(ts: Long): String = DateUtils.getRelativeTimeSpanString(
    ts, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS
).toString()

class Row(
    val title: String,
    val subtitle: String,
    val badge: String?,
    val badgeColor: Int,
    val icon: Drawable?,
    val key: Any?,
)

/** Simple list adapter: icon, bold title, grey subtitle, coloured pill on the right. */
class RowAdapter(private val ctx: Context) : BaseAdapter() {
    var rows: List<Row> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    private class Holder(val root: View, val icon: ImageView, val title: TextView, val sub: TextView, val badge: TextView)

    override fun getCount() = rows.size
    override fun getItem(position: Int): Any = rows[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val h = convertView?.tag as? Holder ?: create()
        val r = rows[position]
        h.icon.setImageDrawable(r.icon)
        h.icon.visibility = if (r.icon == null) View.GONE else View.VISIBLE
        h.title.text = r.title
        h.sub.text = r.subtitle
        if (r.badge == null) {
            h.badge.visibility = View.GONE
        } else {
            h.badge.visibility = View.VISIBLE
            h.badge.text = r.badge
            (h.badge.background as GradientDrawable).setColor(r.badgeColor)
        }
        return h.root
    }

    private fun create(): Holder {
        val c = ctx
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(4), c.dp(10), c.dp(4), c.dp(10))
        }
        val icon = ImageView(c)
        root.addView(icon, LinearLayout.LayoutParams(c.dp(36), c.dp(36)).apply { marginEnd = c.dp(12) })
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val title = c.text("", 15f, bold = true)
        val sub = c.text("", 13f).apply { alpha = 0.7f }
        col.addView(title)
        col.addView(sub)
        root.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val badge = c.text("", 11f, bold = true).apply {
            setTextColor(Color.WHITE)
            setPadding(c.dp(8), c.dp(3), c.dp(8), c.dp(3))
            background = GradientDrawable().apply { cornerRadius = c.dp(10).toFloat() }
        }
        root.addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = c.dp(8) })
        return Holder(root, icon, title, sub, badge).also { root.tag = it }
    }
}

/** The "what do you want to do about this domain?" dialog used by both screens. */
object RuleDialog {
    fun show(a: Activity, pkg: String, appLabel: String, domain: String, onChange: () -> Unit) {
        val rules = Rules.get(a)
        val tracker = Trackers.lookup(domain)
        // Rules for a known tracker apply to the whole tracker domain (e.g. all of doubleclick.net).
        val target = tracker?.domain ?: domain
        val d = rules.decide(pkg, domain, tracker != null)

        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(24), a.dp(8), a.dp(24), a.dp(8))
        }
        body.addView(a.text("App: $appLabel"))
        if (tracker != null) {
            body.addView(a.text("Known tracker: ${tracker.company} — ${tracker.category}").apply { setTextColor(ORANGE) })
        }
        body.addView(
            a.text(if (d.blocked) "Status: BLOCKED (${d.reason})" else "Status: allowed" + (d.reason?.let { " ($it)" } ?: ""))
                .apply { setTextColor(if (d.blocked) RED else GREEN) }
        )
        if (target != domain) body.addView(a.text("Your choice will apply to all of $target").apply { alpha = 0.7f })

        val dialog = AlertDialog.Builder(a).setTitle(domain).setView(body).setNegativeButton("Close", null).create()
        fun option(label: String, enabled: Boolean = true, action: () -> Unit) {
            body.addView(Button(a).apply {
                text = label
                isEnabled = enabled
                setOnClickListener { action(); onChange(); dialog.dismiss() }
            })
        }
        val appKnown = !pkg.startsWith("unknown")
        option("Block for $appLabel only", appKnown) { rules.blockForApp(pkg, target) }
        option("Block in every app") { rules.blockEverywhere(target) }
        option("Allow for $appLabel", appKnown) { rules.allowForApp(pkg, target) }
        option("Remove my rules for this domain") { rules.clearRules(pkg, target) }
        dialog.show()
    }
}
