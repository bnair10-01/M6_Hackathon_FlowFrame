package com.stevencastro.wornrun

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Locale
import kotlin.math.abs

/**
 * The running HUD, laid out from the hand sketch (640×480, scaled to the view):
 *
 *   ┌─────────────────────────────────────────────┐
 *   │ speed              nav arrow      elevation │  top band
 *   │ pace               (+ turn text)  air temp  │
 *   ├─────────────────────────────────────────────┤
 *   │        empty: the runner looks at the world │  middle, never drawn on
 *   ├─────────────────────────────────────────────┤
 *   │ heart rate, cadence              total time │  bottom band
 *   │                              total distance │
 *   └─────────────────────────────────────────────┘
 *
 * The six sketch values share one size. Alerts (off-route, splits, pause)
 * use the nav slot at top center, so the middle stays clear. Black is
 * see-through on the glasses. Text is Noto Sans.
 *
 * Layouts: FULL, or OFF (display asleep: only alerts and turns within 50 m).
 */
class HudView(context: Context) : View(context) {
    enum class Layout { FULL, OFF }

    var layout = Layout.FULL
        set(value) {
            field = value
            invalidate()
        }

    var model = HudModel()
        set(value) {
            field = value
            invalidate()
        }

    private val regular = context.resources.getFont(R.font.noto_sans_regular)
    private val semibold = context.resources.getFont(R.font.noto_sans_semibold)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = semibold }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = regular }
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var alpha = 255

    init {
        setBackgroundColor(Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        val s = width / W
        canvas.save()
        canvas.scale(s, s)
        // Dim in low light so the HUD doesn't glare at night.
        alpha = if (!model.lux.isNaN() && model.lux < 15f) 150 else 255
        val snap = model.snapshot
        if (snap == null || snap.state == RunState.IDLE) drawIdle(canvas) else drawRun(canvas, snap)
        canvas.restore()
    }

    // Idle: nothing is running, so the middle can be used.

    private fun drawIdle(c: Canvas) {
        val title = model.linkProblem ?: if (model.phoneConnected) "Phone connected" else "Waiting for phone…"
        draw(c, title, CENTER_X, 220f, 30f, if (model.linkProblem != null) WARN else ACCENT, Paint.Align.CENTER, text)
        model.routeInfo?.let { draw(c, it, CENTER_X, 262f, 20f, WHITE, Paint.Align.CENTER, label) }
        draw(c, "Start from the phone · long-press for a free run", CENTER_X, 310f, 17f, DIM, Paint.Align.CENTER, label)
        drawVideoStatus(c)
    }

    // Running.

    private fun drawRun(c: Canvas, s: Snapshot) {
        val metric = s.metric
        val full = layout == Layout.FULL

        if (full) {
            // Top-left: current speed, current pace.
            value(c, Units.speed(s.speedMps, metric).substringBefore(' '), speedUnit(metric), LEFT_X, LINE1_Y, Paint.Align.LEFT)
            value(c, Units.pace(s.speedMps, metric), Units.paceUnit(metric), LEFT_X, LINE2_Y, Paint.Align.LEFT)
            rule(c, LEFT_X, LEFT_X + BLOCK_W)

            // Bottom-right: total time, total distance.
            value(c, Units.clock(s.elapsedMs), "", RIGHT_X, LINE3_Y, Paint.Align.RIGHT)
            value(c, Units.distance(s.distanceM, metric), Units.distanceUnit(metric), RIGHT_X, LINE4_Y, Paint.Align.RIGHT)
            rule(c, RIGHT_X - BLOCK_W, RIGHT_X, LINE3_Y + RULE_GAP)

            // Top-right: elevation (climb), air temperature.
            value(c, "↑${Units.elevation(s.elevGainM, metric).substringBefore(' ')}", if (metric) "m" else "ft",
                RIGHT_X, LINE1_Y, Paint.Align.RIGHT)
            value(c, Units.temp(s.airTempC, metric), if (metric) "C" else "F", RIGHT_X, LINE2_Y, Paint.Align.RIGHT)
            rule(c, RIGHT_X - BLOCK_W, RIGHT_X)

            // Bottom-left: heart rate and cadence.
            var x = LEFT_X
            if (!s.heartRateBpm.isNaN()) {
                heart(c, LEFT_X + 9f, LINE4_Y - 8f)
                x = extra(c, "${s.heartRateBpm.toInt()}", "bpm", LEFT_X + 26f) + 18f
            }
            if (!s.cadenceSpm.isNaN()) extra(c, "${s.cadenceSpm.toInt()}", "spm", x)

            if (model.showGps) drawGps(c, s, metric)
        }

        drawTopCenter(c, s, metric)
        drawVideoStatus(c)
    }

    private fun drawVideoStatus(c: Canvas) {
        if (model.videoRecording) draw(c, "● REC", CENTER_X, LINE4_Y, 16f, WARN, Paint.Align.CENTER, label)
    }

    /**
     * The nav slot. In priority order: off-route, a turn within 50 m, the
     * split flash, run state, then the next turn and what's left of the route.
     */
    private fun drawTopCenter(c: Canvas, s: Snapshot, metric: Boolean) {
        val route = s.route
        val off = route?.offRoute
        val turn = route?.nextTurn?.takeIf { !route.finished }
        val nearTurn = turn != null && turn.distanceM < 50

        when {
            off != null -> {
                arrow(c, if (off.relativeBearingDeg.isNaN()) 0f else off.relativeBearingDeg.toFloat(), WARN)
                draw(c, "Off route · ${Units.shortDistance(off.distanceM, metric)}", CENTER_X, NAV_TEXT_Y, 20f, WARN, Paint.Align.CENTER, text)
            }
            nearTurn -> {
                arrow(c, turn!!.angleDeg.toFloat(), ACCENT, big = true)
                draw(c, "${turn.label} · ${Units.shortDistance(turn.distanceM, metric)}", CENTER_X, NAV_TEXT_Y, 20f, ACCENT, Paint.Align.CENTER, text)
            }
            s.split != null -> {
                draw(c, "${if (metric) "Km" else "Mile"} ${s.split.number}", CENTER_X, 62f, 20f, ACCENT, Paint.Align.CENTER, label)
                draw(c, Units.clock(s.split.durationMs), CENTER_X, 104f, VALUE_SIZE, WHITE, Paint.Align.CENTER, text)
            }
            s.state == RunState.FINISHED -> {
                draw(c, "Finished", CENTER_X, 70f, 24f, ACCENT, Paint.Align.CENTER, text)
                draw(c, "avg ${Units.pace(s.avgSpeedMps, metric)}${Units.paceUnit(metric)}", CENTER_X, 104f, 18f, WHITE, Paint.Align.CENTER, label)
            }
            s.state == RunState.PAUSED -> {
                draw(c, "Paused", CENTER_X, 70f, 24f, WARN, Paint.Align.CENTER, text)
                if (layout != Layout.OFF) draw(c, "long-press resume · swipe up finish", CENTER_X, 100f, 14f, DIM, Paint.Align.CENTER, label)
            }
            s.autoPaused && layout != Layout.OFF -> draw(c, "Auto-paused", CENTER_X, 80f, 22f, WARN, Paint.Align.CENTER, text)
            !s.gpsOk && layout != Layout.OFF ->
                draw(c, if (model.phoneConnected) "Waiting for GPS…" else "Phone disconnected", CENTER_X, 80f, 18f, WARN, Paint.Align.CENTER, label)
            route != null && route.finished && layout != Layout.OFF ->
                draw(c, "Route complete", CENTER_X, 80f, 22f, ACCENT, Paint.Align.CENTER, text)
            turn != null && layout == Layout.FULL -> {
                arrow(c, turn.angleDeg.toFloat(), WHITE)
                draw(c, "${turn.label} · ${Units.shortDistance(turn.distanceM, metric)}", CENTER_X, NAV_TEXT_Y, 18f, WHITE, Paint.Align.CENTER, label)
            }
        }

        if (route != null && !route.finished && off == null && layout == Layout.FULL) {
            val eta = if (route.remainingMs >= 0) "${Units.clock(route.remainingMs)} · " else ""
            draw(c, "$eta${Units.distance(route.remainingM, metric)} ${Units.distanceUnit(metric)} left",
                CENTER_X, NAV_TEXT_Y + 22f, 14f, DIM, Paint.Align.CENTER, label)
        }
    }

    /** Temporary debug: the phone's latest fix, so you can see GPS arriving. */
    private fun drawGps(c: Canvas, s: Snapshot, metric: Boolean) {
        val fix = s.lastFix
        if (fix == null) {
            draw(c, "No GPS fix yet", CENTER_X, GPS_Y2, GPS_SIZE, WARN, Paint.Align.CENTER, label)
            return
        }
        val stale = s.fixAgeMs > 3000
        draw(c, String.format(Locale.US, "%.5f, %.5f", fix.lat, fix.lon), CENTER_X, GPS_Y1, GPS_SIZE,
            if (stale) WARN else WHITE, Paint.Align.CENTER, label)
        val parts = mutableListOf("±${Units.accuracy(fix.hAccM, metric)}")
        if (!fix.speedMps.isNaN()) parts += Units.speed(fix.speedMps, metric)
        parts += "${s.fixAgeMs / 1000} s ago"
        if (s.offBody) parts += "off-body"
        draw(c, parts.joinToString(" · "), CENTER_X, GPS_Y2, GPS_SIZE, if (stale) WARN else DIM, Paint.Align.CENTER, label)
    }

    // Drawing helpers.

    /** A small bottom-left value with its unit; returns where it ends. */
    private fun extra(c: Canvas, v: String, unit: String, x: Float): Float {
        draw(c, v, x, LINE4_Y, EXTRA_SIZE, WHITE, Paint.Align.LEFT, text)
        val ux = x + width(v, EXTRA_SIZE, text) + 6f
        draw(c, unit, ux, LINE4_Y, UNIT_SIZE, DIM, Paint.Align.LEFT, label)
        return ux + width(unit, UNIT_SIZE, label)
    }

    /** Heart icon centred on ([cx], [cy]), the same size as the water drop. */
    private fun heart(c: Canvas, cx: Float, cy: Float) {
        path.reset()
        path.moveTo(cx, cy + 10f)
        path.cubicTo(cx - 14f, cy, cx - 10f, cy - 13f, cx, cy - 5f)
        path.cubicTo(cx + 10f, cy - 13f, cx + 14f, cy, cx, cy + 10f)
        shape.style = Paint.Style.FILL
        shape.color = HEART
        shape.alpha = alpha
        c.drawPath(path, shape)
    }

    /** A sketch value: number in the shared size, unit smaller and dim, on one baseline. */
    private fun value(c: Canvas, v: String, unit: String, x: Float, y: Float, align: Paint.Align) {
        val unitW = if (unit.isEmpty()) 0f else width(unit, UNIT_SIZE, label) + 5f
        if (align == Paint.Align.LEFT) {
            draw(c, v, x, y, VALUE_SIZE, WHITE, Paint.Align.LEFT, text)
            if (unit.isNotEmpty()) draw(c, unit, x + width(v, VALUE_SIZE, text) + 5f, y, UNIT_SIZE, DIM, Paint.Align.LEFT, label)
        } else {
            draw(c, v, x - unitW, y, VALUE_SIZE, WHITE, Paint.Align.RIGHT, text)
            if (unit.isNotEmpty()) draw(c, unit, x, y, UNIT_SIZE, DIM, Paint.Align.RIGHT, label)
        }
    }

    /** Hairline between the two lines of a block, as in the sketch. */
    private fun rule(c: Canvas, x1: Float, x2: Float, y: Float = LINE1_Y + RULE_GAP) {
        shape.style = Paint.Style.STROKE
        shape.strokeWidth = 1f
        shape.color = RULE
        shape.alpha = alpha / 3
        c.drawLine(x1, y, x2, y, shape)
    }

    /** Turn arrow at top center, pointing [angle]° from straight ahead (+ right). */
    private fun arrow(c: Canvas, angle: Float, color: Int, big: Boolean = false) {
        val size = if (big) 30f else 22f
        c.save()
        c.translate(CENTER_X, 70f)
        c.rotate(if (abs(angle) >= 165) 180f else angle)
        path.reset()
        path.moveTo(0f, -size)
        path.lineTo(size * 0.75f, size * 0.1f)
        path.lineTo(size * 0.25f, size * 0.1f)
        path.lineTo(size * 0.25f, size)
        path.lineTo(-size * 0.25f, size)
        path.lineTo(-size * 0.25f, size * 0.1f)
        path.lineTo(-size * 0.75f, size * 0.1f)
        path.close()
        shape.style = Paint.Style.FILL
        shape.color = color
        shape.alpha = alpha
        c.drawPath(path, shape)
        c.restore()
    }

    private fun draw(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align, p: Paint) {
        p.textSize = size
        p.color = color
        p.alpha = alpha
        p.textAlign = align
        c.drawText(s, x, y, p)
    }

    private fun width(s: String, size: Float, p: Paint): Float {
        p.textSize = size
        return p.measureText(s)
    }

    private fun speedUnit(metric: Boolean) = if (metric) "km/h" else "mph"

    companion object {
        private const val W = 640f
        private const val CENTER_X = 320f
        private const val LEFT_X = 22f
        private const val RIGHT_X = 618f
        private const val BLOCK_W = 150f
        // Top band sits below the glasses' 24 px status bar; the middle
        // (~170-300) is left empty; the bottom band holds time and distance.
        private const val LINE1_Y = 82f
        private const val LINE2_Y = 132f
        private const val LINE3_Y = 400f
        private const val LINE4_Y = 450f
        private const val RULE_GAP = 14f
        private const val NAV_TEXT_Y = 128f
        private const val VALUE_SIZE = 36f
        private const val UNIT_SIZE = 16f
        private const val EXTRA_SIZE = 20f
        private const val GPS_SIZE = 14f
        private const val GPS_Y1 = 428f
        private const val GPS_Y2 = 448f

        private val WHITE = Color.WHITE
        private val DIM = Color.rgb(120, 140, 160)
        private val RULE = Color.rgb(180, 200, 220)
        private val ACCENT = Color.rgb(80, 220, 255)
        private val WARN = Color.rgb(255, 180, 60)
        private val HEART = Color.rgb(255, 90, 110)
    }
}
