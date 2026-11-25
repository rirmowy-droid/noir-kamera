package pl.noir.kamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        /** Zapas jasności przy sumowaniu w trybie B: 2^5 = 32 klatki zanim "zabraknie skali". */
        const val BULB_HEADROOM = 5f
        const val HEADROOM_LINEAR = 32f
        val F_STOPS = arrayOf("1.4", "2", "2.8", "4", "5.6", "8", "11", "16")
        val F_STOPS_VAL = floatArrayOf(1.4f, 2f, 2.8f, 4f, 5.6f, 8f, 11f, 16f)
    }

    private val params = LookParams()
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: NoirRenderer
    private lateinit var stockName: TextView
    private lateinit var meterText: TextView
    private lateinit var btnMode: Button
    private lateinit var btnIso: Button
    private lateinit var btnShutterSpeed: Button
    private lateinit var btnTake: Button
    private lateinit var flashView: View
    private lateinit var btnAdvance: Button
    private lateinit var roll: Roll
    private lateinit var sound: ShutterSound
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- naciąg filmu i wielokrotna ekspozycja
    @Volatile private var advanced = true
    private var latent: LatentFrame? = null          // tylko na ioExecutor
    @Volatile private var latentExposures = 0
    @Volatile private var developing = false

    private class MaskData(val buf: ByteBuffer?, val w: Int, val h: Int)

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var streamSegmenter: Segmenter? = null
    private var stillSegmenter: Segmenter? = null
    private val segmenting = AtomicBoolean(false)
    private var fullBitmap: Bitmap? = null
    private var orientationListener: OrientationEventListener? = null

    private var stockIndex = 0
    private var manualMode = false
    private var manualSupported = false
    private var isoRange: Range<Int>? = null
    private var expRange: Range<Long>? = null
    private var isoValue = 25
    private var shutterIndex = 4
    private var evComp = 0f
    @Volatile private var meterEv = 0f
    private var frameCount = 0

    private val isoSteps = intArrayOf(25, 50, 100, 200, 400, 800, 1600)
    private val shutterLabels = arrayOf("1/1000", "1/500", "1/250", "1/125", "1/60", "1/30", "1/15", "1/8", "1/4", "1/2", "1 s", "B")
    // dla "B" każda klatka trwa 1/30 s, a aplikacja sumuje światło kolejnych klatek
    private val shutterNs = longArrayOf(
        1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L, 16_666_667L, 33_333_333L,
        66_666_667L, 125_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L, 33_333_333L
    )
    private val bulbIndex = 11

    // ---- tryb B (Bulb): sumowanie światła
    @Volatile private var bulbActive = false
    private var bulbHeld = false
    private var bulbFinishRequested = false
    private var accum: FloatArray? = null
    private var bulbBytes: ByteArray? = null
    private var frameBytes: ByteArray? = null
    private var bulbStartNs = 0L
    private var bulbW = 0
    private var bulbH = 0
    private var bulbRot = 0
    @Volatile private var bulbSeconds = 0f
    private val linLut = FloatArray(256) { Math.pow(it / 255.0, 2.2).toFloat() }
    private val encLut = ByteArray(4096) { (Math.pow(it / 4095.0, 1.0 / 2.2) * 255.0).roundToInt().coerceIn(0, 255).toByte() }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else toast("Bez dostępu do aparatu nic nie zrobię :(")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        glView = findViewById(R.id.glView)
        stockName = findViewById(R.id.stockName)
        meterText = findViewById(R.id.meter)
        btnMode = findViewById(R.id.btnMode)
        btnIso = findViewById(R.id.btnIso)
        btnShutterSpeed = findViewById(R.id.btnShutterSpeed)
        btnTake = findViewById(R.id.btnTake)
        flashView = findViewById(R.id.flash)
        btnAdvance = findViewById(R.id.btnAdvance)
        roll = Roll(this)
        sound = ShutterSound()

        renderer = NoirRenderer(params)
        glView.setEGLContextClientVersion(3)
        glView.preserveEGLContextOnPause = true
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        streamSegmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).build()
        )
        stillSegmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build()
        )

        setStock(if (roll.count > 0) roll.stockIndex.coerceIn(0, FilmStocks.all.size - 1) else 0)
        stockName.setOnClickListener { changeStockByUser() }
        stockName.setOnLongClickListener { pickStock(); true }
        btnAdvance.setOnClickListener { advanceFilm() }
        btnMode.setOnClickListener { toggleMode() }
        btnIso.setOnClickListener { cycleIso() }
        btnShutterSpeed.setOnClickListener { cycleShutter(1) }
        btnShutterSpeed.setOnLongClickListener { cycleShutter(-1); true }
        btnTake.setOnClickListener { if (!isBulb()) takePhoto() }
        btnTake.setOnTouchListener { _, ev ->
            if (!isBulb()) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> startBulb()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopBulb()
            }
            true
        }
        val panelScroll = findViewById<View>(R.id.panelScroll)
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            panelScroll.visibility = if (panelScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        buildPanel(findViewById(R.id.panel))
        updateExposureUi()

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val r = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageCapture?.targetRotation = r
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        orientationListener?.enable()
    }

    override fun onPause() {
        orientationListener?.disable()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        ioExecutor.shutdown()
        streamSegmenter?.close()
        stillSegmenter?.close()
        sound.release()
    }

    // ---------------------------------------------------------------- kamera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val analysisRes = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(analysisRes)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }

            val captureRes = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(captureRes)
                .build()

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis, capture)
                imageCapture = capture
                readCapabilities()
                applyExposure()
            } catch (e: Exception) {
                toast("Nie udało się uruchomić aparatu: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun readCapabilities() {
        val cam = camera ?: return
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val caps = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        manualSupported = caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        expRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    }

    /** Tryb KLISZA: aparat naprawdę naświetla matrycę wybraną czułością i czasem, bez automatyki. */
    private fun applyExposure() {
        val cam = camera ?: return
        val c2 = Camera2CameraControl.from(cam.cameraControl)
        val opts = CaptureRequestOptions.Builder()
        var custom = false
        val manual = manualMode && manualSupported
        if (manual) {
            val iso = isoRange?.clamp(isoValue) ?: isoValue
            val exp = expRange?.clamp(shutterNs[shutterIndex]) ?: shutterNs[shutterIndex]
            opts.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
                .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, max(exp, 33_333_333L))
            custom = true
        }
        // kolorowa klisza ma stały balans bieli: dzienna pod żarówką robi się pomarańczowa,
        // a "T" (sztuczne światło) w słońcu – niebieska. Dokładnie jak prawdziwa emulsja.
        val s = params.stock
        if (s.color && params.fixedWhiteBalance) {
            opts.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                if (s.tungsten) CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT else CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            )
            custom = true
        }
        if (custom) c2.setCaptureRequestOptions(opts.build()) else c2.clearCaptureRequestOptions()
        if (manual) {
            setReciprocity(if (isBulb()) 0f else shutterNs[shutterIndex] / 1e9f)
        } else {
            applyEvComp()
            setReciprocity(0f)
        }
        updateExposureUi()
    }

    /**
     * Efekt Schwarzschilda: przy naświetlaniu dłuższym niż ok. 1/2 s stara emulsja "gubi" światło
     * (p ≈ 0.8) i robi się bardziej kontrastowa.
     */
    private fun setReciprocity(seconds: Float) {
        val tc = 0.5f
        if (seconds <= tc) {
            params.reciprocityStops = 0f
            params.reciprocityContrast = 1f
            return
        }
        val p = if (params.stock.spectral[0] < 0.1f) 0.75f else 0.8f
        val loss = ((1f - p) * ln(seconds / tc.toDouble()) / ln(2.0)).toFloat()
        params.reciprocityStops = -loss
        params.reciprocityContrast = 1f + 0.12f * loss
    }

    private fun isBulb(): Boolean = manualMode && manualSupported && shutterIndex == bulbIndex

    private fun startBulb() {
        if (bulbActive || !btnTake.isEnabled) return
        if (!canExpose(bulb = true)) return
        bulbHeld = true
        sound.firstCurtain()
        btnTake.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        analysisExecutor.execute {
            accum = null
            bulbFinishRequested = false
            bulbStartNs = System.nanoTime()
            bulbActive = true
        }
        btnTake.alpha = 0.5f
    }

    private fun stopBulb() {
        btnTake.alpha = 1f
        if (!bulbHeld) return
        bulbHeld = false
        sound.secondCurtain()
        btnTake.isEnabled = false
        analysisExecutor.execute {
            if (!bulbActive) {
                runOnUiThread { btnTake.isEnabled = true }
                return@execute
            }
            bulbActive = false
            val bytes = bulbBytes
            val w = bulbW
            val h = bulbH
            val rot = bulbRot
            val seconds = bulbSeconds
            if (accum == null || bytes == null) {
                runOnUiThread { btnTake.isEnabled = true }
                return@execute
            }
            accum = null
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            flashView.post {
                flashView.alpha = 0.7f
                flashView.animate().alpha(0f).setDuration(350).start()
            }
            ioExecutor.execute {
                setReciprocity(seconds)
                handleExposure(bmp, rot, BULB_HEADROOM, afterDevelop = { setReciprocity(0f) })
            }
        }
    }

    /** Dodaje światło bieżącej klatki do sumy i podmienia klatkę podglądu na sumę (zakodowaną z zapasem). */
    private fun accumulateBulb(dst: ByteBuffer, w: Int, h: Int, rot: Int) {
        val n = w * h * 4
        var fb = frameBytes
        if (fb == null || fb.size != n) {
            fb = ByteArray(n)
            frameBytes = fb
        }
        dst.position(0)
        dst.get(fb)
        var acc = accum
        if (acc == null || bulbW != w || bulbH != h) {
            acc = FloatArray(w * h * 3)
            accum = acc
            bulbW = w
            bulbH = h
            bulbStartNs = System.nanoTime()
        }
        bulbRot = rot
        var out = bulbBytes
        if (out == null || out.size != n) {
            out = ByteArray(n)
            bulbBytes = out
        }
        val scale = 4095f / HEADROOM_LINEAR
        var j = 0
        var i = 0
        while (i < n) {
            val r = acc[j] + linLut[fb[i].toInt() and 0xFF]
            val g = acc[j + 1] + linLut[fb[i + 1].toInt() and 0xFF]
            val b = acc[j + 2] + linLut[fb[i + 2].toInt() and 0xFF]
            acc[j] = r
            acc[j + 1] = g
            acc[j + 2] = b
            out[i] = encLut[min((r * scale).toInt(), 4095)]
            out[i + 1] = encLut[min((g * scale).toInt(), 4095)]
            out[i + 2] = encLut[min((b * scale).toInt(), 4095)]
            out[i + 3] = 0xFF.toByte()
            i += 4
            j += 3
        }
        dst.position(0)
        dst.put(out)
        dst.position(0)
        bulbSeconds = (System.nanoTime() - bulbStartNs) / 1e9f
        setReciprocity(bulbSeconds)
    }

    private fun applyEvComp() {
        val cam = camera ?: return
        val st = cam.cameraInfo.exposureState
        if (!st.isExposureCompensationSupported) return
        val step = st.exposureCompensationStep.toFloat()
        if (step <= 0f) return
        val range = st.exposureCompensationRange
        val idx = (evComp / step).roundToInt().coerceIn(range.lower, range.upper)
        cam.cameraControl.setExposureCompensationIndex(idx)
    }

    private fun toggleMode() {
        if (!manualMode && camera != null && !manualSupported) {
            toast("Ten telefon nie pozwala na ręczną migawkę i ISO – zostaje tryb AUTO")
            return
        }
        manualMode = !manualMode
        if (manualMode) toast("KLISZA: Ty ustawiasz czas, ISO bierze się z kliszy. Patrz na światłomierz.")
        applyExposure()
    }

    private fun cycleIso() {
        val i = isoSteps.indexOf(isoValue)
        isoValue = isoSteps[(if (i < 0) 0 else i + 1) % isoSteps.size]
        applyExposure()
    }

    private fun cycleShutter(dir: Int) {
        shutterIndex = (shutterIndex + dir + shutterNs.size) % shutterNs.size
        applyExposure()
    }

    private fun updateExposureUi() {
        btnMode.text = if (manualMode) "Tryb: KLISZA (ręczny)" else "Tryb: AUTO"
        val realIso = isoRange?.clamp(isoValue) ?: isoValue
        btnIso.text = if (realIso != isoValue) "ISO $isoValue→$realIso" else "ISO $isoValue"
        btnShutterSpeed.text = shutterLabels[shutterIndex]
        val alpha = if (manualMode) 1f else 0.4f
        btnIso.alpha = alpha
        btnShutterSpeed.alpha = alpha
    }

    private fun setStock(i: Int) {
        stockIndex = i
        val s = FilmStocks.all[i]
        params.stock = s
        isoValue = s.iso
        updateStockLabel()
        applyExposure()
    }

    private fun stockLocked(): Boolean {
        if (roll.enabled && roll.count > 0) {
            toast("W aparacie jest rolka „${params.stock.name}”. Dokończ ją albo wywołaj, żeby założyć inną kliszę.")
            return true
        }
        return false
    }

    private fun changeStockByUser() {
        if (stockLocked()) return
        loadStock((stockIndex + 1) % FilmStocks.all.size)
    }

    private fun pickStock() {
        if (stockLocked()) return
        val names = FilmStocks.all.map { it.name }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Załóż kliszę")
            .setSingleChoiceItems(names, stockIndex) { d, which ->
                loadStock(which)
                d.dismiss()
            }
            .show()
    }

    private fun loadStock(i: Int) {
        roll.stockIndex = i
        setStock(i)
        val s = FilmStocks.all[i]
        if (s.color && params.fixedWhiteBalance) {
            toast(if (s.tungsten) "Klisza do światła sztucznego – w słońcu zdjęcia będą niebieskie" else "Klisza dzienna – pod żarówką zdjęcia będą pomarańczowe")
        }
    }

    private fun updateStockLabel() {
        val s = params.stock
        val frameInfo = when {
            roll.full -> "rolka skończona"
            roll.enabled -> "klatka ${roll.nextFrameNumber}/${roll.loadedSize}"
            else -> "bez rolki"
        }
        val exp = if (latentExposures > 0) " · ekspozycja ${latentExposures + 1}" else ""
        stockName.text = "▸ ${s.name} · $frameInfo$exp"
        btnAdvance.text = when {
            roll.full -> "Wywołaj rolkę"
            params.manualAdvance && (!advanced || latentExposures > 0) -> "Naciągnij ⟳ !"
            else -> "Naciągnij ⟳"
        }
        btnAdvance.alpha = if (roll.full || (params.manualAdvance && (!advanced || latentExposures > 0))) 1f else 0.45f
    }

    /** Czy migawkę można teraz wyzwolić – jak w mechanicznym aparacie. */
    private fun canExpose(bulb: Boolean = false): Boolean {
        if (developing) {
            toast("Trwa wywoływanie rolki…")
            return false
        }
        if (roll.full) {
            toast("Koniec rolki! Wywołaj ją przyciskiem obok spustu.")
            return false
        }
        if (params.manualAdvance && !advanced) {
            if (!bulb && params.multiExposure && latentExposures > 0) return true
            btnTake.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            toast(if (bulb && latentExposures > 0) "Najpierw zakończ wielokrotną ekspozycję – naciągnij film ⟳" else "Spust zablokowany – najpierw naciągnij film ⟳")
            return false
        }
        return true
    }

    private fun currentExposureMs(): Long =
        if (manualMode && manualSupported && !isBulb()) shutterNs[shutterIndex] / 1_000_000L else 17L

    private fun shutterSoundAndFeel() {
        sound.firstCurtain()
        btnTake.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        mainHandler.postDelayed({ sound.secondCurtain() }, max(currentExposureMs(), 25L))
    }

    private fun advanceFilm() {
        if (developing) return
        if (roll.full) {
            developRoll()
            return
        }
        if (!params.manualAdvance) {
            toast("Naciąg jest automatyczny – ręczny włączysz w Ustawieniach")
            return
        }
        if (advanced && latentExposures == 0) {
            toast("Film jest już naciągnięty")
            return
        }
        sound.advance()
        btnAdvance.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        btnAdvance.isEnabled = false
        btnTake.isEnabled = false
        ioExecutor.execute {
            val lf = latent
            latent = null
            latentExposures = 0
            advanced = true
            if (lf != null) {
                developBitmap(
                    lf.bitmap, lf.rotation, LatentFrame.GAIN_STOPS,
                    preMask = MaskData(lf.mask, lf.maskW, lf.maskH), needMask = false
                )
            } else {
                runOnUiThread { btnTake.isEnabled = true }
            }
            runOnUiThread {
                btnAdvance.isEnabled = true
                updateStockLabel()
            }
        }
    }

    /** Wywołanie całej rolki: klatki trafiają do Galerii dopiero teraz. */
    private fun developRoll() {
        if (developing) return
        val frames = roll.frames()
        if (frames.isEmpty()) {
            toast("Rolka jest pusta")
            return
        }
        developing = true
        btnTake.isEnabled = false
        btnAdvance.isEnabled = false
        toast("Wywoływanie: wywoływacz… przerywacz… utrwalacz… płukanie…")
        ioExecutor.execute {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            var ok = 0
            frames.forEachIndexed { i, f ->
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "ROLKA_${stamp}_%02d.jpg".format(i + 1))
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NoirKamera")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { os -> f.inputStream().use { it.copyTo(os) } }
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                        ok++
                    }
                } catch (e: Exception) {
                    // pojedyncza klatka może się nie udać, jak w prawdziwej ciemni
                }
            }
            Thread.sleep(1200)
            roll.reset()
            developing = false
            advanced = true
            runOnUiThread {
                toast("Rolka wywołana: $ok zdjęć w Galerii → Pictures/NoirKamera")
                btnTake.isEnabled = true
                btnAdvance.isEnabled = true
                updateStockLabel()
            }
        }
    }

    // ---------------------------------------------------------------- analiza klatek

    private fun analyze(image: ImageProxy) {
        try {
            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val src = plane.buffer
            val rowStride = plane.rowStride
            val dst = renderer.frameBuffer(w * h * 4)
            src.rewind()
            if (rowStride == w * 4) {
                src.limit(w * h * 4)
                dst.put(src)
            } else {
                for (y in 0 until h) {
                    src.limit(y * rowStride + w * 4)
                    src.position(y * rowStride)
                    dst.put(src)
                }
            }
            src.limit(src.capacity())
            dst.position(0)
            val rot = image.imageInfo.rotationDegrees

            meterEv = measure(dst, w, h)
            val bulbNow = bulbActive
            if (bulbNow) accumulateBulb(dst, w, h, rot)

            if (params.aiEnabled && segmenting.compareAndSet(false, true)) {
                val small = smallUpright(dst, w, h, rot)
                dst.position(0)
                val seg = streamSegmenter
                if (small != null && seg != null) {
                    seg.process(InputImage.fromBitmap(small, 0))
                        .addOnSuccessListener(analysisExecutor) { m -> renderer.submitMask(maskBytes(m), m.width, m.height) }
                        .addOnCompleteListener(analysisExecutor) { segmenting.set(false) }
                } else {
                    segmenting.set(false)
                }
            }

            renderer.commitFrame(w, h, rot, if (bulbNow) BULB_HEADROOM else 0f)
            glView.requestRender()

            frameCount++
            if (frameCount % 8 == 0) runOnUiThread { updateMeterUi() }
        } catch (e: Exception) {
            // pojedyncza zła klatka nie może zatrzymać aparatu
        } finally {
            image.close()
        }
    }

    /** Światłomierz: średnia jasność (z przewagą środka) względem 18% szarości, w działkach przysłony. */
    private fun measure(buf: ByteBuffer, w: Int, h: Int): Float {
        val s = params.stock.spectral
        var sum = 0.0
        var weight = 0.0
        val step = 24
        var y = step / 2
        while (y < h) {
            var x = step / 2
            while (x < w) {
                val i = (y * w + x) * 4
                val r = lin(buf.get(i))
                val g = lin(buf.get(i + 1))
                val b = lin(buf.get(i + 2))
                val dx = (x - w / 2f) / w
                val dy = (y - h / 2f) / h
                val wt = if (dx * dx + dy * dy < 0.06f) 3.0 else 1.0
                sum += (r * s[0] + g * s[1] + b * s[2]) * wt
                weight += wt
                x += step
            }
            y += step
        }
        val mean = max(sum / max(weight, 1.0), 1e-5)
        return (ln(mean / 0.18) / ln(2.0)).toFloat()
    }

    private fun lin(b: Byte): Double = Math.pow((b.toInt() and 0xFF) / 255.0, 2.2)

    private fun updateMeterUi() {
        val ev = meterEv
        meterText.text = if (bulbActive) {
            "B: %.1f s  ·  Schwarzschild %.1f EV  ·  puść, by zakończyć".format(bulbSeconds, params.reciprocityStops)
        } else if (isBulb()) {
            "Tryb B: trzymaj spust tak długo, jak ma trwać naświetlanie"
        } else if (manualMode) {
            val arrow = when {
                ev > 0.7f -> "▲ prześwietlone – krótszy czas"
                ev < -0.7f -> "▼ niedoświetlone – dłuższy czas"
                else -> "● w punkt"
            }
            val rec = if (params.reciprocityStops < -0.05f) "  ·  Schwarzschild %.1f EV".format(params.reciprocityStops) else ""
            "Światłomierz: %+.1f EV   %s%s".format(ev, arrow, rec)
        } else {
            "Automat dobiera światło · w trybie KLISZA naświetlasz sam"
        }
    }

    private fun smallUpright(buf: ByteBuffer, w: Int, h: Int, rot: Int): Bitmap? {
        var full = fullBitmap
        if (full == null || full.width != w || full.height != h) {
            full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            fullBitmap = full
        }
        buf.position(0)
        full!!.copyPixelsFromBuffer(buf)
        return scaledUpright(full, rot, 256)
    }

    private fun scaledUpright(src: Bitmap, rot: Int, target: Int): Bitmap {
        val scale = target.toFloat() / max(src.width, src.height)
        val m = Matrix()
        m.postScale(scale, scale)
        m.postRotate(rot.toFloat())
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun maskBytes(mask: SegmentationMask): ByteBuffer {
        val n = mask.width * mask.height
        val fb = mask.buffer
        fb.rewind()
        val out = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder())
        for (i in 0 until n) {
            val v = (fb.float * 255f).roundToInt().coerceIn(0, 255)
            out.put(v.toByte())
        }
        out.position(0)
        return out
    }

    // ---------------------------------------------------------------- zdjęcie

    private fun takePhoto() {
        val cap = imageCapture ?: return
        if (!btnTake.isEnabled || !canExpose()) return
        shutterSoundAndFeel()
        btnTake.isEnabled = false
        flashView.alpha = 0.7f
        flashView.animate().alpha(0f).setDuration(350).start()
        cap.takePicture(ioExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rot = image.imageInfo.rotationDegrees
                val bytes = try {
                    val b = image.planes[0].buffer
                    b.rewind()
                    val arr = ByteArray(b.remaining())
                    b.get(arr)
                    arr
                } finally {
                    image.close()
                }
                developAndSave(bytes, rot)
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    btnTake.isEnabled = true
                    toast("Błąd zdjęcia: ${exception.message}")
                }
            }
        })
    }

    /** "Ciemnia": dekodowanie, maska AI, wywołanie na GPU w pełnej rozdzielczości i zapis do Galerii. */
    private fun developAndSave(bytes: ByteArray, rot: Int) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val limit = min(renderer.maxTextureSize, 4096)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > limit) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: throw IllegalStateException("Nie można odczytać zdjęcia")
            handleExposure(bmp, rot, 0f)
        } catch (e: Throwable) {
            runOnUiThread {
                btnTake.isEnabled = true
                toast("Nie udało się wywołać zdjęcia: ${e.message}")
            }
        }
    }

    /**
     * Światło trafiło na kliszę. Przy wielokrotnej ekspozycji dokładamy je do obrazu utajonego klatki,
     * w przeciwnym razie klatka od razu idzie do "wywołania".
     */
    private fun handleExposure(bmp: Bitmap, rot: Int, inputGain: Float, afterDevelop: (() -> Unit)? = null) {
        val multi = params.manualAdvance && params.multiExposure && inputGain == 0f
        if (multi) {
            try {
                val m = computeMask(bmp, rot)
                val lf = latent
                if (lf == null) {
                    val n = LatentFrame(bmp, rot)
                    n.addMask(m?.buf, m?.w ?: 0, m?.h ?: 0, rot)
                    latent = n
                } else {
                    lf.add(bmp)
                    lf.addMask(m?.buf, m?.w ?: 0, m?.h ?: 0, rot)
                }
                bmp.recycle()
                latentExposures = latent?.exposures ?: 0
                advanced = false
                runOnUiThread {
                    btnTake.isEnabled = true
                    updateStockLabel()
                    if (latentExposures == 1) toast("Ekspozycja 1 na klatce. Zrób kolejną bez naciągania albo naciągnij film, by zakończyć.")
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    btnTake.isEnabled = true
                    toast("Za mało pamięci na wielokrotną ekspozycję: ${e.message}")
                }
            }
            return
        }
        advanced = !params.manualAdvance
        developBitmap(bmp, rot, inputGain, afterDevelop = afterDevelop)
        runOnUiThread { updateStockLabel() }
    }

    private fun computeMask(bmp: Bitmap, rot: Int): MaskData? {
        val seg = stillSegmenter ?: return null
        if (!params.aiEnabled) return null
        return try {
            val small = scaledUpright(bmp, rot, 512)
            val m = Tasks.await(seg.process(InputImage.fromBitmap(small, 0)))
            MaskData(maskBytes(m), m.width, m.height)
        } catch (e: Exception) {
            null
        }
    }

    private fun developBitmap(
        bmp: Bitmap, rot: Int, inputGain: Float,
        preMask: MaskData? = null, needMask: Boolean = true,
        afterDevelop: (() -> Unit)? = null
    ) {
        try {
            val md = if (needMask) computeMask(bmp, rot) else preMask
            val mw = md?.w ?: 0
            val mh = md?.h ?: 0
            val mb = md?.buf
            glView.queueEvent {
                val out = try {
                    renderer.renderStill(bmp, rot, mb, mw, mh, inputGain)
                } catch (e: Throwable) {
                    null
                }
                bmp.recycle()
                afterDevelop?.invoke()
                ioExecutor.execute { finishSave(out) }
            }
        } catch (e: Throwable) {
            runOnUiThread {
                btnTake.isEnabled = true
                toast("Nie udało się wywołać zdjęcia: ${e.message}")
            }
        }
    }

    private fun finishSave(out: Bitmap?) {
        if (out == null) {
            runOnUiThread {
                btnTake.isEnabled = true
                toast("Nie udało się wywołać zdjęcia")
            }
            return
        }
        var img: Bitmap = out
        try {
            val frameNo = roll.nextFrameNumber
            if (params.filmBorder) {
                try {
                    val framed = FilmBorder.apply(out, frameNo, params.stock.name)
                    out.recycle()
                    img = framed
                } catch (e: Throwable) {
                    img = out
                }
            }
            if (roll.enabled) {
                val finalImg = img
                roll.store { os -> finalImg.compress(Bitmap.CompressFormat.JPEG, 95, os) }
                runOnUiThread {
                    updateStockLabel()
                    if (roll.full) toast("Koniec rolki! Wywołaj ją przyciskiem obok spustu.")
                }
                return
            }
            roll.digitalCounter = frameNo
            val name = "NOIR_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NoirKamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Brak miejsca w Galerii")
            contentResolver.openOutputStream(uri)?.use { os -> img.compress(Bitmap.CompressFormat.JPEG, 95, os) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            runOnUiThread { toast("Zapisano w Galerii → Pictures/NoirKamera") }
        } catch (e: Exception) {
            runOnUiThread { toast("Błąd zapisu: ${e.message}") }
        } finally {
            img.recycle()
            runOnUiThread {
                btnTake.isEnabled = true
                updateStockLabel()
            }
        }
    }

    // ---------------------------------------------------------------- panel ustawień

    private fun buildPanel(panel: LinearLayout) {
        panel.addView(header("CIEMNIA"))
        addSlider(panel, "Gradacja papieru", 0f, 5f, 2f, { "%d (%s)".format(it.roundToInt(), paperName(it.roundToInt())) }, steps = 5) {
            params.paperGrade = it.roundToInt()
        }
        addSlider(panel, "Efekt krawędziowy wywoływacza", 0f, 1f, 0.35f, { "%d%%".format((it * 100).roundToInt()) }) { params.edge = it }
        addSlider(panel, "Przeterminowana klisza", 0f, 1f, 0f, { "%d%%".format((it * 100).roundToInt()) }) { params.expired = it }
        panel.addView(header("KLISZA"))
        addSlider(panel, "Ziarno", 0f, 3f, 1f, { "×%.2f".format(it) }) { params.grainMul = it }
        addSlider(panel, "Poświata świateł (halacja)", 0f, 3f, 1f, { "×%.2f".format(it) }) { params.glowMul = it }
        panel.addView(header("OBIEKTYW"))
        addSlider(panel, "Winieta obiektywu", 0f, 2f, 1f, { "×%.2f".format(it) }) { params.vignetteMul = it }
        addSlider(panel, "Filtr dyfuzyjny (pończocha)", 0f, 1f, 0f, { "%d%%".format((it * 100).roundToInt()) }) { params.diffusion = it }
        addSlider(panel, "Przysłona (głębia ostrości, AI)", 0f, 7f, 7f, { "f/" + F_STOPS[it.roundToInt()] }, steps = 7) {
            params.aperture = apertureBlur(F_STOPS_VAL[it.roundToInt()])
        }
        addSlider(panel, "Ostrość", 0f, 1f, 0f, {
            when {
                it < 0.2f -> "na postaci"
                it > 0.8f -> "na tle"
                else -> "pomiędzy"
            }
        }) { params.focus = it }
        panel.addView(CheckBox(this).apply {
            text = "Wirujące rozmycie (jak Helios)"
            setTextColor(Color.WHITE)
            isChecked = params.swirl
            setOnCheckedChangeListener { _, c -> params.swirl = c }
        })
        panel.addView(header("APARAT ANALOGOWY"))
        val sizes = intArrayOf(0, 12, 24, 36)
        val curSize = sizes.indexOf(roll.nextSize).let { if (it < 0) 3 else it }.toFloat()
        addSlider(panel, "Rolka", 0f, 3f, curSize, {
            val v = sizes[it.roundToInt()]
            if (v == 0) "bez rolki – zdjęcia od razu w Galerii" else "$v klatek"
        }, steps = 3) {
            roll.nextSize = sizes[it.roundToInt()]
            if (roll.count > 0) toast("Nowa długość zadziała od następnej rolki")
            updateStockLabel()
        }
        addCheck(panel, "Ręczne naciąganie filmu", params.manualAdvance) {
            params.manualAdvance = it
            if (!it) advanced = true
            updateStockLabel()
        }
        addCheck(panel, "Wielokrotna ekspozycja (spust bez naciągania)", params.multiExposure) { params.multiExposure = it }
        addCheck(panel, "Wizjer z matówką i klinem ostrości", params.viewfinder) { params.viewfinder = it }
        addCheck(panel, "Ramka negatywu z perforacją na zdjęciach", params.filmBorder) { params.filmBorder = it }
        addCheck(panel, "Dźwięk migawki", sound.enabled) { sound.enabled = it }
        addCheck(panel, "Stały balans bieli kolorowej kliszy (dzienna / żarówka)", params.fixedWhiteBalance) {
            params.fixedWhiteBalance = it
            applyExposure()
        }
        panel.addView(Button(this).apply {
            text = "Przewiń i wywołaj rolkę teraz"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.pill)
            setOnClickListener { developRoll() }
        })

        panel.addView(header("APARAT"))
        addSlider(panel, "Korekta ekspozycji aparatu (AUTO)", -3f, 2f, 0f, { "%+.1f EV".format(it) }) {
            evComp = it
            if (!manualMode) applyEvComp()
        }
        addSlider(panel, "Wywołanie push/pull", -2f, 2f, 0f, { "%+.1f EV".format(it) }) { params.pushPull = it }
        addSlider(panel, "AI: ciemność tła", 0f, 1f, 0.7f, { "%d%%".format((it * 100).roundToInt()) }) { params.aiAmount = it }

        val ai = CheckBox(this).apply {
            text = "AI oddziela postać od tła"
            setTextColor(Color.WHITE)
            isChecked = params.aiEnabled
            setOnCheckedChangeListener { _, c ->
                params.aiEnabled = c
                if (!c) renderer.clearMask()
            }
        }
        val dmg = CheckBox(this).apply {
            text = "Stara taśma (rysy, kurz, migotanie)"
            setTextColor(Color.WHITE)
            isChecked = params.damage
            setOnCheckedChangeListener { _, c -> params.damage = c }
        }
        panel.addView(ai)
        panel.addView(dmg)
    }

    private fun addSlider(
        panel: LinearLayout, title: String, lo: Float, hi: Float, initial: Float,
        fmt: (Float) -> String, steps: Int = 1000, onChange: (Float) -> Unit
    ) {
        val label = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        val seek = SeekBar(this)
        seek.max = steps
        seek.progress = ((initial - lo) / (hi - lo) * steps).roundToInt()
        fun update(p: Int, notify: Boolean) {
            val v = lo + (hi - lo) * p / steps.toFloat()
            label.text = "$title: ${fmt(v)}"
            if (notify) onChange(v)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) update(p, true)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        update(seek.progress, false)
        panel.addView(label)
        panel.addView(seek)
    }

    private fun addCheck(panel: LinearLayout, title: String, initial: Boolean, onChange: (Boolean) -> Unit) {
        panel.addView(CheckBox(this).apply {
            text = title
            setTextColor(Color.WHITE)
            isChecked = initial
            setOnCheckedChangeListener { _, c -> onChange(c) }
        })
    }

    private fun header(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.parseColor("#AAAAAA"))
        textSize = 12f
        setPadding(0, 28, 0, 6)
    }

    private fun paperName(g: Int) = when (g) {
        0 -> "bardzo miękki"
        1 -> "miękki"
        2 -> "normalny"
        3 -> "twardy"
        4 -> "bardzo twardy"
        else -> "ekstra twardy"
    }

    /** Rozmycie jest proporcjonalne do 1/N: f/16 → 0, f/1.4 → 1. */
    private fun apertureBlur(f: Float): Float = ((1f / f - 1f / 16f) / (1f / 1.4f - 1f / 16f)).coerceIn(0f, 1f)

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
