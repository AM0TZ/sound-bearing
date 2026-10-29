package com.amotz.soundbearing

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * 3D direction map on a sphere of world directions.
 *
 * Two mics only measure the angle between the sound and the mic axis, so one
 * reading is a ring of possible directions. The phone's motion sensors give the
 * mic axis in room coordinates at each reading; as the phone is panned and
 * tilted, rings from different axes overlap at the true direction. This map
 * accumulates the rings; its peak is the 3D estimate (azimuth + elevation).
 *
 * World frame (Android rotation vector): X east, Y north, Z up.
 */
class SpatialMap {
    val stepDeg = 4
    val nAz = 360 / stepDeg                 // 90
    val nEl = 180 / stepDeg + 1             // 46, from -90 to +90
    val size = nAz * nEl
    val vx = FloatArray(size)
    val vy = FloatArray(size)
    val vz = FloatArray(size)
    val azDeg = FloatArray(size)
    val elDeg = FloatArray(size)
    val v = FloatArray(size)                // accumulated evidence
    val lf = FloatArray(size)               // evidence-weighted sum of ln(frequency)

    // Device rotation (device -> world, row-major 3x3), set from the sensor thread.
    @Volatile var rot: FloatArray? = null
    // Mic axis in device coordinates (phone's long axis). Fixed to the hardware, not the screen.
    val micAxisDev = floatArrayOf(0f, -1f, 0f)
    // Screen axes in device coordinates for the current display rotation.
    @Volatile var rightDev = floatArrayOf(0f, -1f, 0f)
    @Volatile var upDev = floatArrayOf(1f, 0f, 0f)

    // Width of a ring in units of sin(angle); about ±5° near broadside.
    private val sigma = 0.09f

    // Peak (recomputed by refreshPeak)
    @Volatile var peakIdx = -1
    @Volatile var peakVal = 0f
    @Volatile var sharpness = 0f            // peak / best value elsewhere

    init {
        for (e in 0 until nEl) for (a in 0 until nAz) {
            val i = e * nAz + a
            val el = -90.0 + e * stepDeg
            val az = a * stepDeg.toDouble()
            val ce = cos(el * PI / 180)
            vx[i] = (ce * sin(az * PI / 180)).toFloat()
            vy[i] = (ce * cos(az * PI / 180)).toFloat()
            vz[i] = sin(el * PI / 180).toFloat()
            azDeg[i] = az.toFloat()
            elDeg[i] = el.toFloat()
        }
    }

    fun clear() {
        v.fill(0f)
        lf.fill(0f)
        peakIdx = -1; peakVal = 0f; sharpness = 0f
    }

    fun decay(f: Float) {
        for (i in 0 until size) { v[i] *= f; lf[i] *= f }
    }

    /** Device vector -> world vector using the current rotation (identity if no sensor). */
    fun toWorld(d: FloatArray): FloatArray {
        // No motion sensor: treat device axes as world axes (2D behaviour, still consistent).
        val r = rot ?: return d.copyOf()
        return floatArrayOf(
            r[0] * d[0] + r[1] * d[1] + r[2] * d[2],
            r[3] * d[0] + r[4] * d[1] + r[5] * d[2],
            r[6] * d[0] + r[7] * d[1] + r[8] * d[2]
        )
    }

    /** World vector -> device vector. */
    fun toDevice(w: FloatArray): FloatArray {
        val r = rot ?: return w.copyOf()
        return floatArrayOf(
            r[0] * w[0] + r[3] * w[1] + r[6] * w[2],
            r[1] * w[0] + r[4] * w[1] + r[7] * w[2],
            r[2] * w[0] + r[5] * w[1] + r[8] * w[2]
        )
    }

    /** Add one reading: s = sin(angle from broadside), positive toward screen-right. */
    fun add(s: Float, weight: Float, logF: Float) {
        val m = toWorld(micAxisDev)
        val k = -1f / (2 * sigma * sigma)
        for (i in 0 until size) {
            val d = m[0] * vx[i] + m[1] * vy[i] + m[2] * vz[i] - s
            val g = d * d * k
            if (g > -9f) { val e = weight * exp(g); v[i] += e; lf[i] += e * logF }
        }
    }

    /** Average frequency (Hz) of the sounds that landed in cell i. */
    fun freqAt(i: Int): Float = if (v[i] > 1e-9f) exp(lf[i] / v[i]) else 1000f

    fun refreshPeak() {
        var bi = -1
        var bv = 0f
        for (i in 0 until size) if (v[i] > bv) { bv = v[i]; bi = i }
        if (bi < 0) { peakIdx = -1; peakVal = 0f; sharpness = 0f; return }
        // best value at least 30° away from the peak
        val cosLim = cos(30 * PI / 180).toFloat()
        var second = 0f
        for (i in 0 until size) {
            val dot = vx[i] * vx[bi] + vy[i] * vy[bi] + vz[i] * vz[bi]
            if (dot < cosLim && v[i] > second) second = v[i]
        }
        peakIdx = bi
        peakVal = bv
        sharpness = if (second > 1e-6f) bv / second else 10f
    }

    /** Camera forward (device -Z) as world vector. */
    fun cameraForward() = toWorld(floatArrayOf(0f, 0f, -1f))

    companion object {
        fun azimuthOf(w: FloatArray) = (atan2(w[0].toDouble(), w[1].toDouble()) * 180 / PI).toFloat()
        fun elevationOf(w: FloatArray) = (asin(w[2].coerceIn(-1f, 1f).toDouble()) * 180 / PI).toFloat()
    }
}
