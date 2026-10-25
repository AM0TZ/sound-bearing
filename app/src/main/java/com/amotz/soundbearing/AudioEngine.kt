package com.amotz.soundbearing

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord

/** Reads raw stereo float PCM from one Android audio source and feeds the DSP. */
class AudioEngine(private val dsp: Dsp) {
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var info = ""

    @SuppressLint("MissingPermission")
    fun start(source: Int, name: String): Boolean {
        stop()
        val fs = 48000
        val n = dsp.n
        val minBuf = AudioRecord.getMinBufferSize(fs, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBuf <= 0) { info = "$name: not supported"; return false }
        val rec = try {
            AudioRecord(source, fs, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(minBuf, n * 2 * 4 * 4))
        } catch (e: Exception) {
            info = "$name: ${e.message}"
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            info = "$name: could not open"
            return false
        }
        info = "$name · ${rec.channelCount} ch · ${rec.sampleRate / 1000} kHz"
        dsp.reset()
        running = true
        val t = Thread {
            val buf = FloatArray(n * 2)
            val l = FloatArray(n)
            val r = FloatArray(n)
            try {
                rec.startRecording()
                while (running) {
                    var off = 0
                    while (off < buf.size && running) {
                        val got = rec.read(buf, off, buf.size - off, AudioRecord.READ_BLOCKING)
                        if (got <= 0) { running = false; break }
                        off += got
                    }
                    if (off < buf.size) break
                    for (i in 0 until n) { l[i] = buf[2 * i]; r[i] = buf[2 * i + 1] }
                    dsp.process(l, r, rec.sampleRate)
                }
            } catch (e: Exception) {
                info = "$name: ${e.message}"
            } finally {
                try { rec.stop() } catch (e: Exception) { }
                rec.release()
            }
        }
        t.priority = Thread.MAX_PRIORITY
        t.start()
        thread = t
        return true
    }

    fun stop() {
        running = false
        thread?.join(600)
        thread = null
    }
}
