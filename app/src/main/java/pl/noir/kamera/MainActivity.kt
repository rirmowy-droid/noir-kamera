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
import android.provider.MediaStore
import android.util.Range
import android.util.Size
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
    private val shutterLabels = arrayOf("1/1000", "1/500", "1/250", "1/125", "1/60", "1/30", "1/15", "1/8", "1/4", "1/2", "1 s")
    private val shutterNs = longArrayOf(
        1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L, 16_666_667L, 33_333_333L,
        66_666_667L, 125_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L
    )

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

        setStock(0)
        stockName.setOnClickListener { setStock((stockIndex + 1) % FilmStocks.all.size) }
        btnMode.setOnClickListener { toggleMode() }
        btnIso.setOnClickListener { cycleIso() }
        btnShutterSpeed.setOnClickListener { cycleShutter(1) }
        btnShutterSpeed.setOnLongClickListener { cycleShutter(-1); true }
        btnTake.setOnClickListener { takePhoto() }
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
        if (manualMode && manualSupported) {
            val iso = isoRange?.clamp(isoValue) ?: isoValue
            val exp = expRange?.clamp(shutterNs[shutterIndex]) ?: shutterNs[shutterIndex]
            val opts = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
                .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, max(exp, 33_333_333L))
                .build()
            c2.setCaptureRequestOptions(opts)
        } else {
            c2.clearCaptureRequestOptions()
            applyEvComp()
        }
        updateExposureUi()
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
        stockName.text = "▸ ${s.name}   (dotknij, by zmienić kliszę)"
        if (this::btnMode.isInitialized) applyExposure()
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

            renderer.commitFrame(w, h, rot)
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
        meterText.text = if (manualMode) {
            val arrow = when {
                ev > 0.7f -> "▲ prześwietlone – krótszy czas"
                ev < -0.7f -> "▼ niedoświetlone – dłuższy czas"
                else -> "● w punkt"
            }
            "Światłomierz: %+.1f EV   %s".format(ev, arrow)
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

            var maskBuf: ByteBuffer? = null
            var mw = 0
            var mh = 0
            val seg = stillSegmenter
            if (params.aiEnabled && seg != null) {
                try {
                    val small = scaledUpright(bmp, rot, 512)
                    val m = Tasks.await(seg.process(InputImage.fromBitmap(small, 0)))
                    maskBuf = maskBytes(m)
                    mw = m.width
                    mh = m.height
                } catch (e: Exception) {
                    maskBuf = null
                }
            }

            val mb = maskBuf
            glView.queueEvent {
                val out = try {
                    renderer.renderStill(bmp, rot, mb, mw, mh)
                } catch (e: Throwable) {
                    null
                }
                bmp.recycle()
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
        try {
            val name = "NOIR_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NoirKamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Brak miejsca w Galerii")
            contentResolver.openOutputStream(uri)?.use { os -> out.compress(Bitmap.CompressFormat.JPEG, 95, os) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            runOnUiThread { toast("Zapisano w Galerii → Pictures/NoirKamera") }
        } catch (e: Exception) {
            runOnUiThread { toast("Błąd zapisu: ${e.message}") }
        } finally {
            out.recycle()
            runOnUiThread { btnTake.isEnabled = true }
        }
    }

    // ---------------------------------------------------------------- panel ustawień

    private fun buildPanel(panel: LinearLayout) {
        addSlider(panel, "Kontrast kliszy", 0.5f, 2.0f, 1f, { "×%.2f".format(it) }) { params.contrastMul = it }
        addSlider(panel, "Ziarno", 0f, 3f, 1f, { "×%.2f".format(it) }) { params.grainMul = it }
        addSlider(panel, "Poświata świateł (halacja)", 0f, 3f, 1f, { "×%.2f".format(it) }) { params.glowMul = it }
        addSlider(panel, "Winieta obiektywu", 0f, 2f, 1f, { "×%.2f".format(it) }) { params.vignetteMul = it }
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
        fmt: (Float) -> String, onChange: (Float) -> Unit
    ) {
        val label = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        val seek = SeekBar(this)
        seek.max = 1000
        seek.progress = ((initial - lo) / (hi - lo) * 1000f).roundToInt()
        fun update(p: Int, notify: Boolean) {
            val v = lo + (hi - lo) * p / 1000f
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

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
