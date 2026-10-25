package com.amotz.soundbearing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/** Draws the direction trail, live marker, bearing gauge and readouts over the camera. */
class OverlayView(context: Context, private val dsp: Dsp, private val audio: AudioEngine) : View(context) {
    @Volatile var fov = 65f
    @Volatile var cal = 110f
    @Volatile var message = ""

    private val dens = resources.displayMetrics.density
    private fun dp(v: Float) = v * dens
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val path = Path()
    private val rect = RectF()

    private val stopPos = floatArrayOf(0f, 0.45f, 0.8f, 1f)
    private val stopCol = arrayOf(intArrayOf(31, 184, 166), intArrayOf(255, 176, 32), intArrayOf(255, 77, 61), intArrayOf(255, 236, 220))

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

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        val half = fov / 2f
        val tH = tan(Math.toRadians(half.toDouble())).toFloat()
        fun xOf(th: Float) = w / 2 + (w / 2) * tan(Math.toRadians(th.toDouble())).toFloat() / tH
        val dbShown = dsp.dbfs + cal
        val heat = (dbShown - 35f) / 55f

        // trail curtain and floor strip
        for (i in 0..180) {
            val th = (i - 90).toFloat()
            val v = min(1f, dsp.hist[i] / 5f)
            if (v < 0.02f || abs(th) >= half) continue
            val x0 = xOf(th - 0.5f)
            val x1 = xOf(th + 0.5f) + 1
            fill.color = ramp(0.25f + v * 0.6f, v * 0.22f)
            c.drawRect(x0, 0f, x1, h, fill)
            fill.color = ramp(0.25f + v * 0.6f, 0.25f + v * 0.7f)
            c.drawRect(x0, h - dp(8f), x1, h, fill)
        }

        // angle scale along the floor (stops short of the gauge)
        val gR = min(dp(74f), w * 0.12f)
        val gaugeLeft = w - gR * 2 - dp(60f)
        text.textSize = dp(10f)
        text.textAlign = Paint.Align.CENTER
        text.color = Color.argb(150, 230, 238, 240)
        fill.color = text.color
        var a = -90
        while (a <= 90) {
            if (abs(a) < half) {
                val x = xOf(a.toFloat())
                if (x <= gaugeLeft) {
                    val tick = if (a % 30 == 0) dp(8f) else dp(4f)
                    c.drawRect(x, h - dp(14f), x + dp(1f), h - dp(14f) + tick, fill)
                    if (a % 30 == 0) c.drawText("$a°", x, h - dp(18f), text)
                }
            }
            a += 10
        }

        // live marker
        val age = System.currentTimeMillis() - dsp.lastMs
        val live = dsp.valid && age < 450
        val th = dsp.theta
        if (live) {
            val al = 1f - age / 450f
            if (abs(th) < half) {
                val x = xOf(th)
                val bw = dp(18f) + dp(60f) * (1f - min(1f, dsp.conf * 2f))
                fill.shader = LinearGradient(x - bw, 0f, x + bw, 0f,
                    intArrayOf(ramp(heat, 0f), ramp(heat, 0.38f * al), ramp(heat, 0f)), null, Shader.TileMode.CLAMP)
                c.drawRect(x - bw, 0f, x + bw, h, fill)
                fill.shader = null
                val y = h * 0.5f
                val r = dp(14f) + maxOf(0f, dbShown - 35f) * dp(1.1f)
                stroke.color = ramp(heat, al); stroke.strokeWidth = dp(2.5f)
                c.drawCircle(x, y, r, stroke)
                stroke.color = ramp(heat, al * 0.5f); stroke.strokeWidth = dp(1f)
                c.drawCircle(x, y, r + dp(8f) + dp(6f) * sin(System.currentTimeMillis() / 120.0).toFloat(), stroke)
                fill.color = ramp(heat, al)
                c.drawCircle(x, y, dp(3.5f), fill)
                val side = if (th > 0.5f) " R" else if (th < -0.5f) " L" else ""
                val lbl = "${dbShown.roundToInt()} dB*  ${abs(th.roundToInt())}°$side"
                text.textSize = dp(13f); text.textAlign = Paint.Align.CENTER
                val tw = text.measureText(lbl) + dp(16f)
                val ly = y - r - dp(26f)
                fill.color = Color.argb(190, 10, 14, 17)
                c.drawRect(x - tw / 2, ly - dp(15f), x + tw / 2, ly + dp(7f), fill)
                text.color = ramp(heat, 1f)
                c.drawText(lbl, x, ly + dp(2f), text)
            } else {
                val right = th > 0
                val x = if (right) w - dp(22f) else dp(22f)
                val y = h * 0.5f
                path.reset()
                if (right) { path.moveTo(x + dp(12f), y); path.lineTo(x - dp(6f), y - dp(14f)); path.lineTo(x - dp(6f), y + dp(14f)) }
                else { path.moveTo(x - dp(12f), y); path.lineTo(x + dp(6f), y - dp(14f)); path.lineTo(x + dp(6f), y + dp(14f)) }
                path.close()
                fill.color = ramp(heat, al)
                c.drawPath(path, fill)
                text.textSize = dp(12f); text.color = ramp(heat, al)
                text.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
                c.drawText("${abs(th.roundToInt())}°", if (right) x - dp(10f) else x + dp(10f), y + dp(32f), text)
            }
        }

        // bearing gauge covering the whole left-right range
        val cx = w - gR - dp(22f)
        val cy = h - dp(42f)
        fill.color = Color.argb(180, 10, 14, 17)
        rect.set(cx - gR - dp(10f), cy - gR - dp(10f), cx + gR + dp(10f), cy + gR + dp(10f))
        c.drawArc(rect, 180f, 180f, true, fill)
        rect.set(cx - gR, cy - gR, cx + gR, cy + gR)
        fill.color = Color.argb(30, 255, 176, 32)
        c.drawArc(rect, 270f - half, fov, true, fill)
        stroke.color = Color.argb(64, 230, 238, 240); stroke.strokeWidth = dp(1f)
        c.drawArc(rect, 180f, 180f, false, stroke)
        stroke.strokeWidth = dp(2f)
        var i = 0
        while (i <= 180) {
            val v = min(1f, dsp.hist[i] / 5f)
            if (v >= 0.03f) {
                val ang = Math.toRadians((270 + i - 90).toDouble())
                val ca = cos(ang).toFloat()
                val sa = sin(ang).toFloat()
                val r0 = gR - dp(4f)
                val r1 = r0 - v * gR * 0.45f
                stroke.color = ramp(0.25f + v * 0.6f, 0.3f + v * 0.7f)
                c.drawLine(cx + ca * r0, cy + sa * r0, cx + ca * r1, cy + sa * r1, stroke)
            }
            i += 2
        }
        if (live) {
            val ang = Math.toRadians((270 + th).toDouble())
            stroke.color = ramp(heat, 1f)
            c.drawLine(cx, cy, cx + cos(ang).toFloat() * gR, cy + sin(ang).toFloat() * gR, stroke)
        }
        text.textSize = dp(9f); text.textAlign = Paint.Align.CENTER; text.color = Color.argb(230, 143, 161, 168)
        c.drawText("L", cx - gR - dp(3f), cy + dp(12f), text)
        c.drawText("R", cx + gR + dp(3f), cy + dp(12f), text)

        // readout panel
        val px = dp(12f)
        val py = dp(12f)
        fill.color = Color.argb(185, 10, 14, 17)
        rect.set(px, py, px + dp(230f), py + dp(150f))
        c.drawRoundRect(rect, dp(10f), dp(10f), fill)
        text.textAlign = Paint.Align.LEFT
        text.textSize = dp(30f); text.color = Color.rgb(230, 238, 240)
        val dbTxt = if (dsp.dbfs > -100f) "${dbShown.roundToInt()}" else "--"
        c.drawText(dbTxt, px + dp(12f), py + dp(38f), text)
        val bigW = text.measureText(dbTxt)
        text.textSize = dp(11f); text.color = Color.rgb(143, 161, 168)
        c.drawText("dB*", px + dp(16f) + bigW, py + dp(38f), text)
        val (stTxt, stCol) = when (dsp.stereo) {
            "ok" -> "STEREO OK" to Color.rgb(63, 214, 160)
            "dup" -> "MONO (channels identical)" to Color.rgb(255, 90, 72)
            "onesilent" -> "ONE CHANNEL SILENT" to Color.rgb(255, 90, 72)
            "quiet" -> "NO SIGNAL" to Color.rgb(255, 90, 72)
            else -> "CHECKING…" to Color.rgb(143, 161, 168)
        }
        val lines = listOf(
            "bearing    " + (if (live) "${th.roundToInt()}°" else "—"),
            "confidence " + (if (live) String.format("%.2f", dsp.conf) else "—"),
            "dBFS       " + (if (dsp.dbfs > -100f) String.format("%.1f", dsp.dbfs) else "—"),
            audio.info
        )
        var ly = py + dp(60f)
        for (ln in lines) { c.drawText(ln, px + dp(12f), ly, text); ly += dp(17f) }
        text.color = stCol
        text.textSize = dp(11f)
        c.drawText(stTxt, px + dp(12f), ly + dp(2f), text)

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
}
