package com.amotz.soundbearing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Draws over the camera:
 *  - the 3D direction map projected into the camera view (horizontal AND vertical),
 *  - a marker on the strongest direction once the fix is sharp,
 *  - a radar disc (angle = direction around you, radius = up/down),
 *  - the readout panel.
 */
class OverlayView(
    context: Context,
    private val dsp: Dsp,
    private val audio: AudioEngine,
    private val map: SpatialMap
) : View(context) {
    @Volatile var fov = 65f            // horizontal field of view of the preview
    @Volatile var cal = 110f
    @Volatile var message = ""
    @Volatile var holdMs = 4000f       // how long a detected sound stays marked

    private val dens = resources.displayMetrics.density
    private fun dp(v: Float) = v * dens
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val path = Path()
    private val rect = RectF()
    private var lastPeakMs = 0L

    private val stopPos = floatArrayOf(0f, 0.45f, 0.8f, 1f)
    private val stopCol = arrayOf(intArrayOf(31, 184, 166), intArrayOf(255, 176, 32), intArrayOf(255, 77, 61), intArrayOf(255, 236, 220))

    /** Pitch colour: 80 Hz deep red → 300 Hz orange → 1 kHz green → 3 kHz blue → 8 kHz violet. */
    private fun freqColor(hz: Float, alpha: Float, bright: Float = 1f): Int {
        val t = ((ln(hz.coerceIn(80f, 8000f)) - ln(80f)) / (ln(8000f) - ln(80f)))
        val hue = t * 280f                      // 0 = red … 280 = violet
        val a = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        return Color.HSVToColor(a, floatArrayOf(hue, 0.85f, (0.55f + 0.45f * bright).coerceIn(0f, 1f)))
    }

    private fun hzText(hz: Float) = if (hz >= 1000f) String.format("%.1f kHz", hz / 1000f) else "${(hz / 10f).roundToInt() * 10} Hz"

    private fun ramp(v0: Float, alpha: Float): Int {
        val v = v0.coerceIn(0f, 1f)
        val a = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        for (i in 1 until stopPos.size) {
            if (v <= stopPos[i]) {
                val f = (v - stopPos[i - 1]) / (stopPos[i] - stopPos[i - 1])
                val c0 = stopCol[i - 1]
                val c1 = stopCol[i]
                return Color.argb(a, (c0[0] + (c1[0] - c0[0]) * f).roundToInt(), (c0[1] + (c1[1] - c0[1]) * f).roundToInt(), (c0[2] + (c1[2] - c0[2]) * f).roundToInt())
            }
        }
        val c = stopCol.last()
        return Color.argb(a, c[0], c[1], c[2])
    }

    // camera-space projection result
    private var px = 0f
    private var py = 0f
    private var camR = 0f   // component toward screen-right
    private var camU = 0f   // component toward screen-up
    private var camF = 0f   // component along the camera

    private fun project(wx: Float, wy: Float, wz: Float, w: Float, h: Float, tH: Float, tV: Float): Boolean {
        val d = map.toDevice(floatArrayOf(wx, wy, wz))
        val r = map.rightDev
        val u = map.upDev
        camR = d[0] * r[0] + d[1] * r[1] + d[2] * r[2]
        camU = d[0] * u[0] + d[1] * u[1] + d[2] * u[2]
        camF = -d[2]
        if (camF <= 0.05f) return false
        px = w / 2 + (w / 2) * (camR / camF) / tH
        py = h / 2 - (h / 2) * (camU / camF) / tV
        return true
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        val now = System.currentTimeMillis()
        val tH = tan(Math.toRadians(fov / 2.0)).toFloat()
        val tV = tH * h / w

        if (now - lastPeakMs > 120) { map.refreshPeak(); lastPeakMs = now }
        val peak = map.peakIdx
        val peakVal = map.peakVal
        val locked = peak >= 0 && peakVal > 0.6f && map.sharpness > 1.35f
        val age = (now - dsp.lastMs).toFloat()
        val live = dsp.valid && age < holdMs
        val fresh = dsp.valid && age < 600f
        val al = when {
            !live -> 0f
            age < holdMs * 0.6f -> 1f
            else -> 1f - (age - holdMs * 0.6f) / (holdMs * 0.4f)
        }
        val dbAtHit = dsp.lastDbfs + cal
        val heat = (dbAtHit - 35f) / 55f

        // ---- 1. heat map of the 3D map, projected into the camera view ----
        if (peakVal > 1e-3f) {
            val cellPx = (w / 2) * tan(Math.toRadians(map.stepDeg * 0.75)).toFloat() / tH
            for (i in 0 until map.size) {
                val v = map.v[i] / peakVal
                if (v < 0.25f) continue
                if (!project(map.vx[i], map.vy[i], map.vz[i], w, h, tH, tV)) continue
                if (px < -cellPx || px > w + cellPx || py < -cellPx || py > h + cellPx) continue
                val s = cellPx / camF
                fill.color = freqColor(map.freqAt(i), (v - 0.25f) * 0.55f, v)
                c.drawCircle(px, py, s, fill)
            }
        }

        // ---- 2. marker on the 3D peak ----
        val relAz: Float
        val elev: Float
        if (peak >= 0) {
            val fwd = map.cameraForward()
            relAz = wrap180(map.azDeg[peak] - SpatialMap.azimuthOf(fwd))
            elev = map.elDeg[peak]
        } else { relAz = 0f; elev = 0f }

        if (peak >= 0 && live && locked) {
            val pHz = map.freqAt(peak)
            val col = freqColor(pHz, al)
            val labelCol = freqColor(pHz, 1f)
            val dirTxt = dirText(relAz, elev)
            val lbl = "${dbAtHit.roundToInt()} dB*  ${hzText(map.freqAt(peak))}  $dirTxt"
            if (project(map.vx[peak], map.vy[peak], map.vz[peak], w, h, tH, tV) && px in 0f..w && py in 0f..h) {
                val x = px
                val y = py
                val r = dp(16f) + max(0f, dbAtHit - 35f) * dp(1.1f)
                // crosshair brackets
                stroke.color = col; stroke.strokeWidth = dp(2.5f)
                c.drawCircle(x, y, r, stroke)
                stroke.strokeWidth = dp(2f)
                val g = r + dp(10f)
                c.drawLine(x - g, y, x - r + dp(4f), y, stroke); c.drawLine(x + r - dp(4f), y, x + g, y, stroke)
                c.drawLine(x, y - g, x, y - r + dp(4f), stroke); c.drawLine(x, y + r - dp(4f), x, y + g, stroke)
                if (fresh) {
                    stroke.color = freqColor(pHz, al * 0.5f); stroke.strokeWidth = dp(1f)
                    c.drawCircle(x, y, r + dp(14f) + dp(6f) * sin(now / 120.0).toFloat(), stroke)
                }
                drawLabel(c, lbl, x, y - g - dp(10f), labelCol)
            } else {
                // off screen or behind: arrow on the edge, pointing toward it
                var ax = camR
                var ay = camU
                if (hypot(ax, ay) < 1e-3f) { ax = 0f; ay = -1f }
                val ang = atan2(-ay, ax)                         // screen angle
                val mx = w / 2 - dp(40f)
                val my = h / 2 - dp(40f)
                val k = min(mx / max(1e-3f, abs(cos(ang))), my / max(1e-3f, abs(sin(ang))))
                val x = w / 2 + cos(ang) * k
                val y = h / 2 + sin(ang) * k
                path.reset()
                path.moveTo(x + cos(ang) * dp(16f), y + sin(ang) * dp(16f))
                path.lineTo(x + cos(ang + 2.5f) * dp(14f), y + sin(ang + 2.5f) * dp(14f))
                path.lineTo(x + cos(ang - 2.5f) * dp(14f), y + sin(ang - 2.5f) * dp(14f))
                path.close()
                fill.color = col
                c.drawPath(path, fill)
                drawLabel(c, lbl, (x - cos(ang) * dp(60f)).coerceIn(dp(90f), w - dp(90f)), (y - sin(ang) * dp(34f)).coerceIn(dp(30f), h - dp(20f)), labelCol)
            }
        }

        // ---- 3. radar ----
        drawRadar(c, w, h, now, peak, locked, live, al, heat)

        // ---- 4. readout panel ----
        val pxL = dp(12f)
        val pyT = dp(12f)
        fill.color = Color.argb(185, 10, 14, 17)
        rect.set(pxL, pyT, pxL + dp(240f), pyT + dp(208f))
        c.drawRoundRect(rect, dp(10f), dp(10f), fill)
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(30f); text.color = Color.rgb(230, 238, 240)
        val dbNow = dsp.dbfs + cal
        val dbTxt = if (dsp.dbfs > -100f) "${dbNow.roundToInt()}" else "--"
        c.drawText(dbTxt, pxL + dp(12f), pyT + dp(38f), text)
        val bigW = text.measureText(dbTxt)
        text.textSize = dp(11f); text.color = Color.rgb(143, 161, 168)
        c.drawText("dB*", pxL + dp(16f) + bigW, pyT + dp(38f), text)
        val (stTxt, stCol) = when (dsp.stereo) {
            "ok" -> "STEREO OK" to Color.rgb(63, 214, 160)
            "dup" -> "MONO (channels identical)" to Color.rgb(255, 90, 72)
            "onesilent" -> "ONE CHANNEL SILENT" to Color.rgb(255, 90, 72)
            "quiet" -> "NO SIGNAL" to Color.rgb(255, 90, 72)
            else -> "CHECKING…" to Color.rgb(143, 161, 168)
        }
        val fixTxt = when {
            peak < 0 -> "waiting for sound"
            locked -> dirText(relAz, elev)
            else -> "pan + tilt phone to lock"
        }
        val lines = listOf(
            "direction  $fixTxt",
            "pitch      " + (if (live) hzText(dsp.lastFreq) else "—"),
            "2-mic      " + (if (live) "${dsp.theta.roundToInt()}° L/R" else "—"),
            "fix        " + fixQuality(peak, locked),
            "confidence " + (if (live) String.format("%.2f", dsp.conf) else "—"),
            audio.info
        )
        var ly = pyT + dp(60f)
        for (ln in lines) { c.drawText(ln, pxL + dp(12f), ly, text); ly += dp(17f) }
        text.color = stCol
        c.drawText(stTxt, pxL + dp(12f), ly + dp(2f), text)
        // pitch colour legend
        val lx0 = pxL + dp(12f)
        val lx1 = pxL + dp(228f)
        val lyB = ly + dp(12f)
        val steps = 48
        for (k in 0 until steps) {
            val f = 80f * Math.pow(100.0, k / (steps - 1.0)).toFloat()
            fill.color = freqColor(f, 1f)
            val xa = lx0 + (lx1 - lx0) * k / steps
            c.drawRect(xa, lyB, xa + (lx1 - lx0) / steps + 1f, lyB + dp(6f), fill)
        }
        text.textSize = dp(9f); text.color = Color.rgb(143, 161, 168)
        for ((f, lbl) in listOf(100f to "100", 300f to "300", 1000f to "1k", 3000f to "3k", 8000f to "8k Hz")) {
            val xf = lx0 + (lx1 - lx0) * (ln(f) - ln(80f)) / (ln(8000f) - ln(80f))
            text.textAlign = if (f >= 8000f) Paint.Align.RIGHT else Paint.Align.CENTER
            c.drawText(lbl, if (f >= 8000f) lx1 else xf, lyB + dp(17f), text)
        }
        text.textAlign = Paint.Align.LEFT

        if (message.isNotEmpty()) {
            text.textSize = dp(13f); text.textAlign = Paint.Align.CENTER; text.color = Color.WHITE
            val mw = min(w - dp(40f), text.measureText(message) + dp(24f))
            fill.color = Color.argb(220, 40, 12, 10)
            rect.set(w / 2 - mw / 2, h - dp(70f), w / 2 + mw / 2, h - dp(40f))
            c.drawRoundRect(rect, dp(8f), dp(8f), fill)
            c.drawText(message, w / 2, h - dp(50f), text)
        }

        postInvalidateOnAnimation()
    }

    /**
     * Radar disc, heading-up: top = where the camera points, bottom = behind you.
     * Radius = elevation: centre = straight up, middle ring = level, edge = straight down.
     */
    private fun drawRadar(c: Canvas, w: Float, h: Float, now: Long, peak: Int, locked: Boolean, live: Boolean, al: Float, heat: Float) {
        val R = min(dp(88f), h * 0.26f)
        val cx = w - R - dp(16f)
        val cy = h - R - dp(16f)
        val fwd = map.cameraForward()
        val camAz = SpatialMap.azimuthOf(fwd)
        fun pos(az: Float, el: Float): Pair<Float, Float> {
            val rel = Math.toRadians(wrap180(az - camAz).toDouble())
            val rr = R * (90f - el) / 180f
            return Pair(cx + (sin(rel) * rr).toFloat(), cy - (cos(rel) * rr).toFloat())
        }

        // disc
        fill.shader = RadialGradient(cx, cy, R, Color.argb(215, 8, 22, 20), Color.argb(215, 6, 12, 14), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, R, fill)
        fill.shader = null
        stroke.strokeWidth = dp(1f)
        stroke.color = Color.argb(70, 63, 214, 160)
        c.drawCircle(cx, cy, R * 0.25f, stroke)
        c.drawCircle(cx, cy, R * 0.75f, stroke)
        stroke.color = Color.argb(140, 63, 214, 160)
        c.drawCircle(cx, cy, R * 0.5f, stroke)       // level ring
        c.drawCircle(cx, cy, R, stroke)
        stroke.color = Color.argb(50, 63, 214, 160)
        c.drawLine(cx - R, cy, cx + R, cy, stroke)
        c.drawLine(cx, cy - R, cx, cy + R, stroke)

        // camera view wedge
        val half = fov / 2f
        fill.color = Color.argb(34, 255, 176, 32)
        rect.set(cx - R, cy - R, cx + R, cy + R)
        c.drawArc(rect, -90f - half, fov, true, fill)

        // sweep
        val sweepDeg = (now % 3000L) / 3000f * 360f
        val sg = SweepGradient(cx, cy, intArrayOf(Color.argb(0, 63, 214, 160), Color.argb(0, 63, 214, 160), Color.argb(110, 63, 214, 160)), floatArrayOf(0f, 0.8f, 1f))
        val m = android.graphics.Matrix(); m.setRotate(sweepDeg - 90f, cx, cy); sg.setLocalMatrix(m)
        fill.shader = sg
        c.drawCircle(cx, cy, R, fill)
        fill.shader = null
        val sr = Math.toRadians((sweepDeg - 90f).toDouble())
        stroke.color = Color.argb(170, 63, 214, 160); stroke.strokeWidth = dp(1.5f)
        c.drawLine(cx, cy, cx + (cos(sr) * R).toFloat(), cy + (sin(sr) * R).toFloat(), stroke)

        // map blips
        val pv = map.peakVal
        if (pv > 1e-3f) {
            for (i in 0 until map.size step 1) {
                val v = map.v[i] / pv
                if (v < 0.35f) continue
                val (x, y) = pos(map.azDeg[i], map.elDeg[i])
                fill.color = freqColor(map.freqAt(i), (v - 0.3f) * 0.9f, v)
                c.drawCircle(x, y, dp(1.6f) + v * dp(2.2f), fill)
            }
        }
        // peak blip
        if (peak >= 0 && locked) {
            val (x, y) = pos(map.azDeg[peak], map.elDeg[peak])
            val a = if (live) max(al, 0.45f) else 0.45f
            fill.color = freqColor(map.freqAt(peak), a)
            c.drawCircle(x, y, dp(5f), fill)
            stroke.color = freqColor(map.freqAt(peak), a); stroke.strokeWidth = dp(1.5f)
            c.drawCircle(x, y, dp(9f), stroke)
        }

        // labels
        text.textSize = dp(9f); text.textAlign = Paint.Align.CENTER; text.color = Color.argb(200, 143, 161, 168)
        c.drawText("FRONT", cx, cy - R - dp(4f), text)
        c.drawText("BEHIND", cx, cy + R + dp(11f), text)
        c.drawText("up", cx + dp(9f), cy - dp(3f), text)
        c.drawText("level", cx + R * 0.5f * 0.71f + dp(14f), cy - R * 0.5f * 0.71f, text)
        c.drawText("down", cx + R * 0.71f + dp(4f), cy - R * 0.71f - dp(2f), text)
    }

    private fun drawLabel(c: Canvas, s: String, x: Float, y: Float, col: Int) {
        text.textSize = dp(13f); text.textAlign = Paint.Align.CENTER
        val tw = text.measureText(s) + dp(16f)
        fill.color = Color.argb(195, 10, 14, 17)
        rect.set(x - tw / 2, y - dp(15f), x + tw / 2, y + dp(7f))
        c.drawRoundRect(rect, dp(5f), dp(5f), fill)
        text.color = col
        c.drawText(s, x, y + dp(2f), text)
    }

    private fun dirText(relAz: Float, el: Float): String {
        val a = relAz.roundToInt()
        val side = when {
            abs(a) <= 2 -> "ahead"
            abs(a) >= 178 -> "behind"
            a > 0 -> "${a}° R"
            else -> "${-a}° L"
        }
        val e = el.roundToInt()
        val vert = when {
            abs(e) <= 2 -> "level"
            e > 0 -> "${e}° up"
            else -> "${-e}° down"
        }
        return "$side, $vert"
    }

    private fun fixQuality(peak: Int, locked: Boolean): String {
        if (peak < 0) return "—"
        val q = String.format("%.1f", map.sharpness)
        return if (locked) "$q locked" else "$q rough"
    }

    private fun wrap180(a: Float): Float {
        var x = a % 360f
        if (x > 180f) x -= 360f
        if (x < -180f) x += 360f
        return x
    }
}
