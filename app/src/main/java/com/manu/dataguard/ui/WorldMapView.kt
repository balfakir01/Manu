package com.manu.dataguard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.manu.dataguard.data.CountrySummary
import com.manu.dataguard.data.World
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * A flat world map with a line from the phone's country to every country that received
 * data. Thicker lines and bigger dots mean more lookups; red means trackers were involved.
 */
class WorldMapView(context: Context, private val world: World) : View(context) {

    companion object {
        private const val LAT_TOP = 84f
        private const val LAT_BOTTOM = -58f     // Antarctica holds no servers; crop it
        val BLUE = Color.parseColor("#1E88E5")
    }

    var home: String? = null
        set(v) { field = v; invalidate() }

    var data: List<CountrySummary> = emptyList()
        set(v) { field = v; rebuildHighlights(); invalidate() }

    var selected: String? = null
        set(v) { field = v; invalidate() }

    var onCountryTapped: ((String) -> Unit)? = null

    private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 128, 128, 128) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.argb(90, 128, 128, 128)
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var landPath = Path()
    private val countryPaths = HashMap<String, Path>()
    private var highlighted: List<Pair<Path, Int>> = emptyList()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * (LAT_TOP - LAT_BOTTOM) / 360f).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        landPath = Path()
        countryPaths.clear()
        for (c in world.countries) {
            if (c.rings.isEmpty()) continue
            val p = Path()
            for (ring in c.rings) {
                for (i in ring.indices step 2) {
                    val x = x(ring[i]); val y = y(ring[i + 1])
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            countryPaths[c.code] = p
            landPath.addPath(p)
        }
        rebuildHighlights()
    }

    private fun rebuildHighlights() {
        val most = data.maxOfOrNull { it.lookups } ?: 1
        highlighted = data.mapNotNull { s ->
            val path = countryPaths[s.country] ?: return@mapNotNull null
            val strength = 40 + (110 * ln(1.0 + s.lookups) / ln(1.0 + most)).toInt()
            path to Color.argb(strength, Color.red(colorFor(s)), Color.green(colorFor(s)), Color.blue(colorFor(s)))
        }
    }

    private fun colorFor(s: CountrySummary) = if (s.trackerDomains > 0) RED else BLUE

    private fun x(lon: Float) = (lon + 180f) / 360f * width
    private fun y(lat: Float) = (LAT_TOP - lat) / (LAT_TOP - LAT_BOTTOM) * height

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(landPath, landPaint)
        for ((path, color) in highlighted) {
            highlightPaint.color = color
            canvas.drawPath(path, highlightPaint)
        }
        canvas.drawPath(landPath, borderPaint)

        val most = data.maxOfOrNull { it.lookups } ?: 1
        val origin = home?.let { world.byCode[it] }
        val d = resources.displayMetrics.density

        // Lines first, so the dots sit on top of them.
        if (origin != null) {
            val ox = x(origin.lon); val oy = y(origin.lat)
            for (s in data) {
                if (s.country == home) continue
                val c = world.byCode[s.country] ?: continue
                val tx = x(c.lon); val ty = y(c.lat)
                val weight = ln(1.0 + s.lookups).toFloat() / ln(1.0 + most).toFloat()
                linePaint.color = colorFor(s)
                linePaint.alpha = if (selected == null || selected == s.country) 200 else 50
                linePaint.strokeWidth = d * (0.8f + 2.5f * weight)
                val arc = Path().apply {
                    moveTo(ox, oy)
                    // Bow the line upward so routes don't overlap the land they cross.
                    val lift = min(height * 0.25f, 0.2f * kotlin.math.abs(tx - ox) + 0.2f * kotlin.math.abs(ty - oy))
                    quadTo((ox + tx) / 2, (oy + ty) / 2 - lift, tx, ty)
                }
                canvas.drawPath(arc, linePaint)
            }
        }

        for (s in data) {
            val c = world.byCode[s.country] ?: continue
            val weight = ln(1.0 + s.lookups).toFloat() / ln(1.0 + most).toFloat()
            val r = d * (2.5f + 4.5f * weight)
            dotPaint.color = colorFor(s)
            dotPaint.alpha = if (selected == null || selected == s.country) 255 else 110
            canvas.drawCircle(x(c.lon), y(c.lat), r, dotPaint)
            if (selected == s.country) {
                ringPaint.color = dotPaint.color
                ringPaint.strokeWidth = d * 1.5f
                canvas.drawCircle(x(c.lon), y(c.lat), r + d * 4, ringPaint)
            }
        }

        if (origin != null) {
            val hx = x(origin.lon); val hy = y(origin.lat)
            dotPaint.color = GREEN; dotPaint.alpha = 255
            canvas.drawCircle(hx, hy, d * 5, dotPaint)
            ringPaint.color = GREEN; ringPaint.strokeWidth = d * 2
            canvas.drawCircle(hx, hy, d * 9, ringPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action != MotionEvent.ACTION_UP) return false
        val maxDist = 28 * resources.displayMetrics.density
        var best: String? = null
        var bestDist = Float.MAX_VALUE
        for (s in data) {
            val c = world.byCode[s.country] ?: continue
            val dx = x(c.lon) - event.x; val dy = y(c.lat) - event.y
            val dist = dx * dx + dy * dy
            if (dist < bestDist) { bestDist = dist; best = s.country }
        }
        if (best != null && bestDist <= max(maxDist * maxDist, 0f)) {
            performClick()
            onCountryTapped?.invoke(best)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
