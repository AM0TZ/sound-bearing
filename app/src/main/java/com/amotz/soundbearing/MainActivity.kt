package com.amotz.soundbearing

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity(), SensorEventListener {
    private val dsp = Dsp()
    private val spatial = SpatialMap()
    private val sensors by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val audio = AudioEngine(dsp)
    private lateinit var overlay: OverlayView
    private lateinit var previewView: PreviewView
    private lateinit var srcBtn: Button
    private lateinit var flipBtn: Button
    private lateinit var panel: ScrollView
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("sound_bearing", MODE_PRIVATE) }

    // Android audio sources to try; phones differ in which one delivers true stereo.
    private val sources = listOf(
        MediaRecorder.AudioSource.CAMCORDER to "Camcorder",
        MediaRecorder.AudioSource.UNPROCESSED to "Unprocessed",
        MediaRecorder.AudioSource.MIC to "Mic",
        MediaRecorder.AudioSource.VOICE_RECOGNITION to "Voice recognition"
    )
    private var srcIdx = 0
    private var autoProbe = true
    private var probeTried = 0
    private var probeWaits = 0
    private var audioOn = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.CAMERA] == true) startCamera()
        if (res[Manifest.permission.RECORD_AUDIO] == true) { if (!audioOn) startAudio() }
        else overlay.message = "Microphone permission denied. Allow it in Android Settings › Apps › Sound Bearing › Permissions."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loadPrefs()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(10, 14, 17)) }
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        dsp.map = spatial
        overlay = OverlayView(this, dsp, audio, spatial)
        overlay.fov = prefs.getFloat("fov", 65f)
        overlay.cal = prefs.getFloat("cal", 110f)
        setHold(prefs.getFloat("hold", 4f))
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        // top-right buttons
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        flipBtn = smallButton("") { dsp.flip = !dsp.flip; dsp.clear(); saveFloat("flip", if (dsp.flip) 1f else 0f); updateButtons() }
        srcBtn = smallButton("") { autoProbe = false; srcIdx = (srcIdx + 1) % sources.size; startAudio() }
        val setBtn = smallButton("Settings") { toggleSettings() }
        val clrBtn = smallButton("Clear") { dsp.clear() }
        listOf(flipBtn, srcBtn, clrBtn, setBtn).forEach { bar.addView(it) }

        // settings panel
        panel = ScrollView(this).apply { visibility = ScrollView.GONE; setBackgroundColor(Color.argb(235, 10, 14, 17)) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(56), dp(16), dp(16)) }
        panel.addView(col)
        col.addView(Button(this).apply {
            text = "Close settings"
            isAllCaps = false
            setTextColor(Color.rgb(26, 18, 5))
            setBackgroundColor(Color.rgb(255, 176, 32))
            setOnClickListener { toggleSettings() }
        })
        addSlider(col, "How long marks stay on screen", 1, 15, prefs.getFloat("hold", 4f).toInt(), { "$it s" }) { setHold(it.toFloat()); saveFloat("hold", it.toFloat()) }
        addSlider(col, "Mic spacing", 50, 200, (dsp.spacing * 1000).toInt(), { "${it / 10f} cm" }) { dsp.spacing = it / 1000f; saveFloat("spacing", dsp.spacing) }
        addSlider(col, "Camera field of view", 35, 110, overlay.fov.toInt(), { "$it°" }) { overlay.fov = it.toFloat(); saveFloat("fov", overlay.fov) }
        addSlider(col, "Lowest frequency used", 100, 2000, dsp.fLo.toInt(), { "$it Hz" }) { dsp.fLo = it.toFloat(); saveFloat("fLo", dsp.fLo) }
        addSlider(col, "Highest frequency used", 1000, 12000, dsp.fHi.toInt(), { "$it Hz" }) { dsp.fHi = it.toFloat(); saveFloat("fHi", dsp.fHi) }
        addSlider(col, "Ignore sounds below", 0, 70, (dsp.gate + 90).toInt(), { "${it - 90} dBFS" }) { dsp.gate = (it - 90).toFloat(); saveFloat("gate", dsp.gate) }
        addSlider(col, "Minimum confidence", 5, 60, (dsp.minConf * 100).toInt(), { String.format("%.2f", it / 100f) }) { dsp.minConf = it / 100f; saveFloat("conf", dsp.minConf) }
        addSlider(col, "dB offset (match a sound meter)", 60, 140, overlay.cal.toInt(), { "+$it" }) { overlay.cal = it.toFloat(); saveFloat("cal", overlay.cal) }
        root.addView(panel, FrameLayout.LayoutParams(dp(320), -1, Gravity.END))
        // buttons go on top of the panel so Settings can always close it
        root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        // tapping the camera view outside the panel also closes it
        overlay.setOnClickListener { if (panel.visibility == ScrollView.VISIBLE) toggleSettings() }

        setContentView(root)
        updateButtons()
        requestOrStart()
    }

    private fun requestOrStart() {
        val need = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) startCamera() else permLauncher.launch(need.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        updateScreenAxes()
        val rv = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rv != null) sensors.registerListener(this, rv, SensorManager.SENSOR_DELAY_GAME)
        else overlay.message = "No motion sensor: up/down can't be measured on this phone."
        if (::overlay.isInitialized && hasMic() && !audioOn) startAudio()
    }

    override fun onPause() {
        super.onPause()
        sensors.unregisterListener(this)
        main.removeCallbacksAndMessages(null)
        audio.stop()
        audioOn = false
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (e: Exception) {
                overlay.message = "Camera unavailable: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startAudio() {
        main.removeCallbacksAndMessages(null)
        val (src, name) = sources[srcIdx]
        val ok = audio.start(src, name)
        audioOn = true
        updateButtons()
        if (!ok && autoProbe) { nextProbe(); return }
        if (autoProbe) { probeWaits = 0; main.postDelayed(::checkProbe, 1500) }
        else overlay.message = ""
    }

    // Auto mode: try each source until one delivers two different channels.
    private fun checkProbe() {
        if (!autoProbe) return
        when (dsp.stereo) {
            "ok" -> { autoProbe = false; overlay.message = ""; prefs.edit().putInt("src", srcIdx).apply() }
            "dup", "onesilent" -> nextProbe()
            else -> if (++probeWaits < 4) main.postDelayed(::checkProbe, 1000) else nextProbe()
        }
    }

    private fun nextProbe() {
        probeTried++
        if (probeTried >= sources.size) {
            autoProbe = false
            overlay.message = "No audio source gave true stereo on this phone. Tap the source button to try them manually."
            srcIdx = 0
            audio.start(sources[0].first, sources[0].second)
            updateButtons()
            return
        }
        srcIdx = (srcIdx + 1) % sources.size
        startAudio()
    }

    private fun updateButtons() {
        flipBtn.text = if (dsp.flip) "L/R swapped" else "Swap L/R"
        srcBtn.text = (if (autoProbe) "Auto: " else "Source: ") + sources[srcIdx].second
    }

    override fun onSensorChanged(e: SensorEvent) {
        val r = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(r, e.values)
        spatial.rot = r
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenAxes()
    }

    // Which device axes point to screen-right and screen-up for the current rotation.
    @Suppress("DEPRECATION")
    private fun updateScreenAxes() {
        when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> { spatial.rightDev = floatArrayOf(0f, -1f, 0f); spatial.upDev = floatArrayOf(1f, 0f, 0f) }
            Surface.ROTATION_270 -> { spatial.rightDev = floatArrayOf(0f, 1f, 0f); spatial.upDev = floatArrayOf(-1f, 0f, 0f) }
            Surface.ROTATION_180 -> { spatial.rightDev = floatArrayOf(-1f, 0f, 0f); spatial.upDev = floatArrayOf(0f, -1f, 0f) }
            else -> { spatial.rightDev = floatArrayOf(1f, 0f, 0f); spatial.upDev = floatArrayOf(0f, 1f, 0f) }
        }
        dsp.clear()
    }

    private fun toggleSettings() {
        panel.visibility = if (panel.visibility == ScrollView.VISIBLE) ScrollView.GONE else ScrollView.VISIBLE
    }

    // Marker stays for `sec`; the trail fades to ~5% over three times that.
    private fun setHold(sec: Float) {
        overlay.holdMs = sec * 1000f
        val framesPerSec = 48000f / dsp.n
        dsp.decay = Math.exp(Math.log(0.05) / (sec * 3f * framesPerSec)).toFloat()
    }

    private fun loadPrefs() {
        dsp.spacing = prefs.getFloat("spacing", 0.14f)
        dsp.fLo = prefs.getFloat("fLo", 300f)
        dsp.fHi = prefs.getFloat("fHi", 6000f)
        dsp.gate = prefs.getFloat("gate", -60f)
        dsp.minConf = prefs.getFloat("conf", 0.25f)
        dsp.flip = prefs.getFloat("flip", 0f) > 0.5f
        srcIdx = prefs.getInt("src", 0).coerceIn(0, sources.size - 1)
    }

    private fun saveFloat(key: String, v: Float) { prefs.edit().putFloat(key, v).apply() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(Color.rgb(230, 238, 240))
        setBackgroundColor(Color.argb(190, 10, 14, 17))
        setPadding(dp(12), dp(6), dp(12), dp(6))
        minHeight = 0
        minimumHeight = 0
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun addSlider(parent: LinearLayout, label: String, min: Int, max: Int, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit) {
        val title = TextView(this).apply { setTextColor(Color.rgb(143, 161, 168)); textSize = 12f; setPadding(0, dp(10), 0, 0) }
        val bar = SeekBar(this)
        bar.min = min
        bar.max = max
        bar.progress = value.coerceIn(min, max)
        title.text = "$label: ${fmt(bar.progress)}"
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                title.text = "$label: ${fmt(p)}"
                if (fromUser) onChange(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        parent.addView(title)
        parent.addView(bar)
    }
}
