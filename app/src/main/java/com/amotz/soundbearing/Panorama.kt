package com.amotz.soundbearing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Equirectangular 360° panorama built from camera frames placed by the phone's
 * orientation (no image matching), plus rendering of the sound map on top.
 *
 * Column 0..W-1 = azimuth relative to the start heading (centre column = start
 * direction), row 0..H-1 = elevation +90 (top) .. -90 (bottom).
 */
class Panorama(val W: Int = 2048, val H: Int = 1024, private val startAz: Float) {
    val px = IntArray(W * H)                 // ARGB, 0 = not filled
    private val best = FloatArray(W * H)     // how central the pixel was in its frame (higher wins)
    private val sinAz = FloatArray(W)
    private val cosAz = FloatArray(W)
    private val sinEl = FloatArray(H)
    private val cosEl = FloatArray(H)
    @Volatile var frames = 0

    init {
        for (x in 0 until W) {
            val az = Math.toRadians((azOfCol(x)).toDouble())
            sinAz[x] = sin(az).toFloat(); cosAz[x] = cos(az).toFloat()
        }
        for (y in 0 until H) {
            val el = Math.toRadians(elOfRow(y).toDouble())
            sinEl[y] = sin(el).toFloat(); cosEl[y] = cos(el).toFloat()
        }
    }

    /** World azimuth (deg, 0 = north, clockwise) for a column. */
    fun azOfCol(x: Int) = startAz + ((x + 0.5f) / W - 0.5f) * 360f
    fun elOfRow(y: Int) = 90f - (y + 0.5f) / H * 180f

    /**
     * Place one camera frame. rot = device->world 3x3 (row-major), right/up = screen axes in
     * device coordinates, fovH = horizontal field of view of the frame in degrees.
     */
    fun addFrame(bmp: Bitmap, rot: FloatArray, right: FloatArray, up: FloatArray, fovH: Float) {
        val bw = bmp.width
        val bh = bmp.height
        val src = IntArray(bw * bh)
        bmp.getPixels(src, 0, bw, 0, 0, bw, bh)
        val tH = tan(Math.toRadians(fovH / 2.0)).toFloat()
        val tV = tH * bh / bw
        // camera axes in world coordinates
        fun w(d: FloatArray) = floatArrayOf(
            rot[0] * d[0] + rot[1] * d[1] + rot[2] * d[2],
            rot[3] * d[0] + rot[4] * d[1] + rot[5] * d[2],
            rot[6] * d[0] + rot[7] * d[1] + rot[8] * d[2])
        val R = w(right)
        val U = w(up)
        val F = w(floatArrayOf(0f, 0f, -1f))
        val cosLim = (1f / sqrt(1f + tH * tH + tV * tV)) * 0.98f   // frame's corner angle
        for (y in 0 until H) {
            val se = sinEl[y]; val ce = cosEl[y]
            // quick row reject: this row's elevation band can't reach the frame
            val rowBest = ce * sqrt(F[0] * F[0] + F[1] * F[1]) + se * F[2]
            if (rowBest < cosLim) continue
            val base = y * W
            for (x in 0 until W) {
                val dx = ce * sinAz[x]; val dy = ce * cosAz[x]; val dz = se
                val f = dx * F[0] + dy * F[1] + dz * F[2]
                if (f < cosLim) continue
                val u = (dx * R[0] + dy * R[1] + dz * R[2]) / f / tH
                val v = (dx * U[0] + dy * U[1] + dz * U[2]) / f / tV
                if (u < -1f || u > 1f || v < -1f || v > 1f) continue
                val score = 2f - abs(u) - abs(v)
                val i = base + x
                if (score <= best[i]) continue
                val sx = ((u + 1f) * 0.5f * (bw - 1)).roundToInt()
                val sy = ((1f - v) * 0.5f * (bh - 1)).roundToInt()
                px[i] = src[sy * bw + sx] or (0xFF shl 24)
                best[i] = score
            }
        }
        frames++
    }

    /** Fraction of the eye-level band (±30°) already filled. */
    fun coverage(): Float {
        val y0 = ((90f - 30f) / 180f * H).toInt()
        val y1 = ((90f + 30f) / 180f * H).toInt()
        var filled = 0
        var total = 0
        var y = y0
        while (y < y1) {
            var x = 0
            while (x < W) { total++; if (px[y * W + x] != 0) filled++; x += 8 }
            y += 8
        }
        return if (total > 0) filled.toFloat() / total else 0f
    }

    data class Source(val az: Float, val el: Float, val hz: Float, val db: Float, val strength: Float)

    /** Find distinct sources in the map: local maxima at least 25° apart. */
    fun findSources(map: SpatialMap, cal: Float, maxN: Int = 6): List<Source> {
        val peak = map.v.maxOrNull() ?: 0f
        if (peak <= 1e-4f) return emptyList()
        val cand = (0 until map.size).filter { map.v[it] >= 0.35f * peak }.sortedByDescending { map.v[it] }
        val picked = ArrayList<Int>()
        val cosSep = cos(Math.toRadians(25.0)).toFloat()
        for (i in cand) {
            if (picked.size >= maxN) break
            val far = picked.all { j -> map.vx[i] * map.vx[j] + map.vy[i] * map.vy[j] + map.vz[i] * map.vz[j] < cosSep }
            if (far) picked.add(i)
        }
        return picked.map { i -> Source(map.azDeg[i], map.elDeg[i], map.freqAt(i), map.dbAt(i) + cal, map.v[i] / peak) }
    }

    /** Render panorama + sound map + labelled sources. Returns the finished bitmap. */
    fun render(map: SpatialMap, cal: Float, scale: Float): Pair<Bitmap, List<Source>> {
        val out = IntArray(W * H)
        val peak = (map.v.maxOrNull() ?: 0f).coerceAtLeast(1e-6f)
        for (y in 0 until H) {
            val el = elOfRow(y)
            for (x in 0 until W) {
                val i = y * W + x
                var p = px[i]
                if (p == 0) {
                    // unfilled: dark grid so the frame of reference is still visible
                    val grid = (x % (W / 24) == 0) || (y % (H / 12) == 0)
                    p = if (grid) Color.rgb(34, 44, 48) else Color.rgb(14, 18, 21)
                }
                // sound map overlay
                val cell = cellIndex(map, azOfCol(x), el)
                val v = map.v[cell] / peak
                if (v > 0.2f) {
                    val a = ((v - 0.2f) / 0.8f).coerceIn(0f, 1f) * 0.6f
                    val c = freqColor(map.freqAt(cell))
                    p = blend(p, c, a)
                }
                out[i] = p
            }
        }
        val bmp = Bitmap.createBitmap(out, W, H, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
        val sources = findSources(map, cal)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 22f * scale }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = 16f * scale; color = Color.WHITE }

        // compass ticks relative to the start direction
        small.textAlign = Paint.Align.CENTER
        for (deg in -180 until 180 step 45) {
            val x = ((deg + 180f) / 360f) * W
            paint.color = Color.argb(150, 255, 255, 255)
            c.drawRect(x - 1, H / 2f - 6 * scale, x + 1, H / 2f + 6 * scale, paint)
            val lbl = when (deg) { 0 -> "START"; -180 -> "BEHIND"; else -> (if (deg > 0) "${deg}° R" else "${-deg}° L") }
            drawTag(c, lbl, x, H / 2f + 24 * scale, small, Color.argb(150, 10, 14, 17))
        }

        // sources
        sources.forEachIndexed { n, s ->
            var dAz = s.az - startAz
            while (dAz > 180f) dAz -= 360f
            while (dAz < -180f) dAz += 360f
            val x = ((dAz + 180f) / 360f) * W
            val y = (90f - s.el) / 180f * H
            val col = freqColor(s.hz)
            val r = (18f + 26f * s.strength) * scale
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f * scale
            paint.color = Color.WHITE
            c.drawCircle(x, y, r + 3 * scale, paint)
            paint.color = col
            paint.strokeWidth = 3f * scale
            c.drawCircle(x, y, r, paint)
            paint.style = Paint.Style.FILL
            c.drawCircle(x, y, 5f * scale, paint)
            val dir = (if (abs(dAz) < 3) "ahead" else if (dAz > 0) "${dAz.roundToInt()}° R" else "${(-dAz).roundToInt()}° L") +
                ", " + (if (abs(s.el) < 3) "level" else if (s.el > 0) "${s.el.roundToInt()}° up" else "${(-s.el).roundToInt()}° down")
            val lbl = "#${n + 1}  ${hz(s.hz)}  ${s.db.roundToInt()} dB*  $dir"
            text.color = col
            val ty = if (y - r - 16 * scale > 30 * scale) y - r - 16 * scale else y + r + 34 * scale
            val tw = text.measureText(lbl) + 20 * scale
            val tx = x.coerceIn(tw / 2, W - tw / 2)
            paint.color = Color.argb(205, 10, 14, 17)
            c.drawRoundRect(RectF(tx - tw / 2, ty - 26 * scale, tx + tw / 2, ty + 9 * scale), 8 * scale, 8 * scale, paint)
            text.textAlign = Paint.Align.CENTER
            c.drawText(lbl, tx, ty, text)
        }

        // footer
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        small.textAlign = Paint.Align.LEFT
        drawTag(c, "Sound Bearing 360 · $stamp · ${sources.size} source(s) · colour = pitch, red low → violet high", 12 * scale + 200 * scale, H - 14 * scale, small, Color.argb(190, 10, 14, 17), left = true)
        return Pair(bmp, sources)
    }

    private fun drawTag(c: Canvas, s: String, x: Float, y: Float, p: Paint, bg: Int, left: Boolean = false) {
        val tw = p.measureText(s)
        val pad = p.textSize * 0.4f
        val x0 = if (left) 12f else x - tw / 2 - pad
        val bgP = Paint().apply { color = bg }
        c.drawRect(x0, y - p.textSize - pad * 0.3f, x0 + tw + pad * 2, y + pad, bgP)
        val old = p.textAlign
        p.textAlign = Paint.Align.LEFT
        c.drawText(s, x0 + pad, y, p)
        p.textAlign = old
    }

    private fun cellIndex(map: SpatialMap, az: Float, el: Float): Int {
        var a = az % 360f
        if (a < 0) a += 360f
        val ai = (a / map.stepDeg).roundToInt() % map.nAz
        val ei = ((el + 90f) / map.stepDeg).roundToInt().coerceIn(0, map.nEl - 1)
        return ei * map.nAz + ai
    }

    companion object {
        fun freqColor(hz: Float): Int {
            val t = (ln(hz.coerceIn(80f, 8000f)) - ln(80f)) / (ln(8000f) - ln(80f))
            return Color.HSVToColor(floatArrayOf(t * 280f, 0.85f, 1f))
        }

        fun hz(f: Float) = if (f >= 1000f) String.format("%.1f kHz", f / 1000f) else "${(f / 10f).roundToInt() * 10} Hz"

        private fun blend(a: Int, b: Int, t: Float): Int {
            val r = (Color.red(a) * (1 - t) + Color.red(b) * t).roundToInt()
            val g = (Color.green(a) * (1 - t) + Color.green(b) * t).roundToInt()
            val bl = (Color.blue(a) * (1 - t) + Color.blue(b) * t).roundToInt()
            return Color.rgb(r, g, bl)
        }

        /** JPEG with Google Photo Sphere (GPano) metadata so galleries open it as a 360° photo. */
        fun jpegWith360Metadata(bmp: Bitmap): ByteArray {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, bos)
            val jpg = bos.toByteArray()
            val w = bmp.width
            val h = bmp.height
            val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/" GPano:ProjectionType="equirectangular" GPano:UsePanoramaViewer="True" GPano:FullPanoWidthPixels="$w" GPano:FullPanoHeightPixels="$h" GPano:CroppedAreaImageWidthPixels="$w" GPano:CroppedAreaImageHeightPixels="$h" GPano:CroppedAreaLeftPixels="0" GPano:CroppedAreaTopPixels="0"/></rdf:RDF></x:xmpmeta>"""
            val header = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
            val body = xmp.toByteArray(Charsets.UTF_8)
            val len = 2 + header.size + body.size
            val seg = ByteArrayOutputStream()
            seg.write(0xFF); seg.write(0xE1); seg.write(len shr 8); seg.write(len and 0xFF)
            seg.write(header); seg.write(body)
            // insert after SOI and after a JFIF APP0 segment if present
            var pos = 2
            if (jpg.size > 6 && (jpg[2].toInt() and 0xFF) == 0xFF && (jpg[3].toInt() and 0xFF) == 0xE0) {
                pos = 4 + (((jpg[4].toInt() and 0xFF) shl 8) or (jpg[5].toInt() and 0xFF))
            }
            val out = ByteArrayOutputStream()
            out.write(jpg, 0, pos)
            out.write(seg.toByteArray())
            out.write(jpg, pos, jpg.size - pos)
            return out.toByteArray()
        }
    }
}
