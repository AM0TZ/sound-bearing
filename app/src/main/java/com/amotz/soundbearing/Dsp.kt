package com.amotz.soundbearing

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Direction finding from two microphones with GCC-PHAT.
 * process() runs on the audio thread; the overlay reads the @Volatile results.
 */
class Dsp(val n: Int = 2048) {
    // settings (written from the UI thread)
    @Volatile var spacing = 0.14f      // metres between the two mics
    @Volatile var fLo = 300f
    @Volatile var fHi = 6000f
    @Volatile var gate = -60f          // dBFS
    @Volatile var minConf = 0.25f
    @Volatile var flip = false

    // results
    val hist = FloatArray(181)         // direction trail, 1° bins, -90..+90
    @Volatile var theta = 0f
    @Volatile var valid = false
    @Volatile var conf = 0f
    @Volatile var dbfs = -120f
    @Volatile var lastMs = 0L
    @Volatile var lastDbfs = -120f     // loudness at the last detection
    @Volatile var decay = 0.985f       // per-frame fade of the direction trail
    @Volatile var stereo = "checking"  // checking | ok | dup | onesilent | quiet

    private var accE = 0.0
    private var accD = 0.0
    private var accL = 0.0
    private var accR = 0.0
    private var frames = 0

    private val logN = (ln(n.toDouble()) / ln(2.0)).roundToInt()
    private val rev = IntArray(n) { i ->
        var r = 0
        var x = i
        repeat(logN) { r = (r shl 1) or (x and 1); x = x shr 1 }
        r
    }
    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { sin(2 * PI * it / n) }
    private val hann = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
    private val r1 = DoubleArray(n)
    private val i1 = DoubleArray(n)
    private val r2 = DoubleArray(n)
    private val i2 = DoubleArray(n)
    private val gr = DoubleArray(n)
    private val gi = DoubleArray(n)

    fun reset() {
        accE = 0.0; accD = 0.0; accL = 0.0; accR = 0.0; frames = 0
        stereo = "checking"
        dbfs = -120f
        clear()
    }

    fun clear() {
        hist.fill(0f)
        valid = false
    }

    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        val sg = if (inverse) 1.0 else -1.0
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in 0 until half) {
                    val a = i + j
                    val b = a + half
                    val wr = cosT[k]
                    val wi = sg * sinT[k]
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    k += step
                }
                i += size
            }
            size = size shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n.toDouble(); im[i] /= n.toDouble() }
    }

    fun process(left: FloatArray, right: FloatArray, fs: Int) {
        var eL = 0.0
        var eR = 0.0
        var d = 0.0
        for (i in 0 until n) {
            val a = left[i].toDouble()
            val b = right[i].toDouble()
            eL += a * a; eR += b * b
            val t = a - b
            d += t * t
        }
        accE = accE * 0.9 + eL + eR
        accD = accD * 0.9 + d
        accL = accL * 0.9 + eL
        accR = accR * 0.9 + eR
        frames++
        if (frames > 8) {
            stereo = when {
                accE < 1e-9 -> "quiet"
                accD / accE < 1e-7 -> "dup"
                accR / accE < 1e-6 || accL / accE < 1e-6 -> "onesilent"
                else -> "ok"
            }
        }
        val db = (10 * log10((eL + eR) / (2 * n) + 1e-14)).toFloat()
        dbfs = if (dbfs < -100f) db else dbfs * 0.7f + db * 0.3f
        for (i in hist.indices) hist[i] *= decay
        if (db < gate || stereo != "ok") return

        for (i in 0 until n) {
            r1[i] = left[i] * hann[i]; i1[i] = 0.0
            r2[i] = right[i] * hann[i]; i2[i] = 0.0
            gr[i] = 0.0; gi[i] = 0.0
        }
        fft(r1, i1, false)
        fft(r2, i2, false)

        val kLo = max(1, ceil(fLo * n / fs).toInt())
        val kHi = min(n / 2 - 1, floor(max(fHi, fLo + 100f) * n / fs).toInt())
        var nb = 0
        for (k in kLo..kHi) {
            // whitened cross-spectrum L * conj(R)
            val xr = r1[k] * r2[k] + i1[k] * i2[k]
            val xi = i1[k] * r2[k] - r1[k] * i2[k]
            val m = hypot(xr, xi) + 1e-12
            gr[k] = xr / m; gi[k] = xi / m
            gr[n - k] = xr / m; gi[n - k] = -xi / m
            nb++
        }
        if (nb == 0) return
        fft(gr, gi, true)

        val maxLag = ceil(spacing / SPEED_OF_SOUND * fs).toInt() + 2
        var best = -1e9
        var bl = 0
        for (l in -maxLag..maxLag) {
            val v = gr[(l + n) % n]
            if (v > best) { best = v; bl = l }
        }
        val y0 = gr[(bl - 1 + n) % n]
        val y2 = gr[(bl + 1 + n) % n]
        val den = y0 - 2 * best + y2
        val off = if (den < 0) 0.5 * (y0 - y2) / den else 0.0
        val c = max(0.0, best * n / (2.0 * nb)).toFloat()
        if (c < minConf) return

        // positive lag = left channel lags = sound reached the right mic first
        val s = (((bl + off) / fs) * SPEED_OF_SOUND / spacing).coerceIn(-1.0, 1.0)
        var th = (asin(s) * 180 / PI).toFloat()
        if (flip) th = -th
        val now = System.currentTimeMillis()
        theta = if (valid && now - lastMs < 500) theta + 0.35f * (th - theta) else th
        valid = true
        lastMs = now
        lastDbfs = db
        conf = c
        val w = c * ((db - gate) / 40f).coerceIn(0.15f, 1f)
        for (b in -12..12) {
            val i = th.roundToInt() + 90 + b
            if (i in 0..180) hist[i] += w * exp(-(b * b) / 32f)
        }
    }

    companion object {
        const val SPEED_OF_SOUND = 343.0
    }
}
