package com.vindovinou.ilook

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CaptureRequest
import android.media.MediaActionSound
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.vindovinou.ilook.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class Mode { VIDEO, PHOTO }

    /** Photo formats, as long side : short side. */
    private enum class Format(val label: String, val ratio: Float, val viewRatio: String) {
        R4_3("4:3", 4f / 3f, "3:4"),
        R16_9("16:9", 16f / 9f, "9:16"),
        R1_1("1:1", 1f, "1:1"),
    }

    private enum class TrayItem { FLASH, FORMAT, TIMER, EXPOSURE, STYLES, GRID }

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("ilook", MODE_PRIVATE) }

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var quickTake = false

    private var processor: LutSurfaceProcessor? = null
    private var lut: Lut3D? = null

    private var mode = Mode.PHOTO
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_AUTO
    private var torch = false
    private var format = Format.R4_3
    private var timerSeconds = 0
    private var grid = false
    private var hdr = true
    private var style: PhotoStyle = Styles.ALL[0]
    private var openTrayItem: TrayItem? = null
    private var lastUri: Uri? = null
    private var inFlight = 0
    private var countingDown = false
    private var uiRotation = 0f
    private var surfaceRotation = Surface.ROTATION_0

    private val sound = MediaActionSound()

    /** iPhone 17 Pro Max lens buttons. ".5" maps to the S25 Ultra's widest available (0.6x). */
    private val zoomStops = listOf(0.5f to ".5", 1f to "1×", 2f to "2", 4f to "4", 8f to "8")
    private val zoomViews = mutableListOf<TextView>()
    private val trayViews = mutableMapOf<TrayItem, Pair<ImageView, TextView>>()

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val snapped = when (orientation) {
                    in 45..134 -> 90
                    in 135..224 -> 180
                    in 225..314 -> 270
                    else -> 0
                }
                val rot = when (snapped) {
                    90 -> Surface.ROTATION_270
                    180 -> Surface.ROTATION_180
                    270 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (rot != surfaceRotation) {
                    surfaceRotation = rot
                    imageCapture?.targetRotation = rot
                    videoCapture?.targetRotation = rot
                    rotateUi(if (snapped == 0) 0f else (360 - snapped).toFloat())
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        style = Styles.byId(prefs.getString("style", null))
        hdr = prefs.getBoolean("hdr", true)
        flashMode = prefs.getInt("flash", ImageCapture.FLASH_MODE_AUTO)
        format = Format.entries.getOrElse(prefs.getInt("format", 0)) { Format.R4_3 }
        timerSeconds = prefs.getInt("timer", 0)
        grid = prefs.getBoolean("grid", false)
        lut = Lut3D(style)
        processor = LutSurfaceProcessor(lut!!)
        sound.load(MediaActionSound.SHUTTER_CLICK)

        buildZoomBar()
        buildTray()
        setupShutter()
        setupGestures()
        b.flipButton.setOnClickListener {
            haptic(it)
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
            else CameraSelector.LENS_FACING_BACK
            b.flipButton.animate().rotationBy(180f).setDuration(250).start()
            bindCamera()
        }
        b.flashButton.setOnClickListener { haptic(it); cycleFlash() }
        b.hdrButton.setOnClickListener {
            haptic(it)
            hdr = !hdr
            prefs.edit().putBoolean("hdr", hdr).apply()
            refreshUi()
            showInfo(if (hdr) "Smart HDR activé" else "Smart HDR désactivé")
        }
        b.chevronButton.setOnClickListener { haptic(it); toggleTray() }
        b.modeVideo.setOnClickListener { setMode(Mode.VIDEO) }
        b.modePhoto.setOnClickListener { setMode(Mode.PHOTO) }
        b.thumbnail.setOnClickListener { openLast() }
        b.exposureSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val cam = camera ?: return
                val es = cam.cameraInfo.exposureState
                cam.cameraControl.setExposureCompensationIndex(es.exposureCompensationRange.lower + progress)
                refreshTrayValues()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        refreshUi()
        b.modeBar.post { centerMode(animate = false) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        orientationListener.enable()
    }

    override fun onPause() {
        super.onPause()
        orientationListener.disable()
        stopRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        processor?.release()
        sound.release()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Volume buttons fire the shutter, like on iPhone.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event?.repeatCount == 0) onShutterTap()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------- Camera ----------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun buildImageCapture(): ImageCapture {
        val builder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flashMode)
            .setJpegQuality(100)
            .setTargetRotation(surfaceRotation)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    // Cap at ~12 MP: the S25 Ultra can expose 50/200 MP modes, which the
                    // full-resolution pipeline cannot hold in memory.
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(4000, 3000),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                    .build(),
            )
        // Ask the ISP for clean, un-oversharpened frames; our own pipeline adds Apple-like detail.
        Camera2Interop.Extender(builder)
            .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        return builder.build()
    }

    private fun buildVideoCapture(): VideoCapture<Recorder> {
        // iPhone default: 1080p.
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)))
            .build()
        return VideoCapture.Builder(recorder).setTargetRotation(surfaceRotation).build()
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val proc = processor ?: return
        val aspect = if (mode == Mode.VIDEO) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val preview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
            .build()
            .also { it.surfaceProvider = b.previewView.surfaceProvider }
        val previewOnly = LutEffect(CameraEffect.PREVIEW, proc)
        val previewAndVideo = LutEffect(CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE, proc)

        // Most capable configuration first, then simpler fallbacks.
        val attempts: List<() -> Pair<List<UseCase>, CameraEffect?>> = if (mode == Mode.PHOTO) {
            listOf(
                { Pair(listOf(preview, buildImageCapture(), buildVideoCapture()), previewAndVideo) },
                { Pair(listOf(preview, buildImageCapture()), previewOnly) },
                { Pair(listOf(preview, buildImageCapture()), null) },
            )
        } else {
            listOf(
                { Pair(listOf(preview, buildVideoCapture()), previewAndVideo) },
                { Pair(listOf(preview, buildVideoCapture()), null) },
            )
        }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        imageCapture = null
        videoCapture = null
        for (attempt in attempts) {
            val (useCases, effect) = attempt()
            try {
                provider.unbindAll()
                val group = UseCaseGroup.Builder().apply {
                    useCases.forEach { addUseCase(it) }
                    effect?.let { addEffect(it) }
                }.build()
                camera = provider.bindToLifecycle(this, selector, group)
                @Suppress("UNCHECKED_CAST")
                useCases.forEach {
                    when (it) {
                        is ImageCapture -> imageCapture = it
                        is VideoCapture<*> -> videoCapture = it as VideoCapture<Recorder>
                    }
                }
                break
            } catch (e: Exception) {
                Log.w(TAG, "Bind attempt failed, trying a simpler configuration", e)
            }
        }
        camera?.cameraInfo?.zoomState?.observe(this) { updateZoomBar(it.zoomRatio) }
        if (mode == Mode.VIDEO && torch) camera?.cameraControl?.enableTorch(true)
        refreshUi()
    }

    // ---------------- Shutter ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupShutter() {
        val longPress = Runnable {
            // QuickTake: hold the shutter in Photo mode to film.
            if (mode == Mode.PHOTO && videoCapture != null && recording == null) {
                quickTake = true
                startRecording()
            }
        }
        b.shutterButton.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    if (mode == Mode.PHOTO) v.postDelayed(longPress, 450)
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    v.removeCallbacks(longPress)
                    if (quickTake) stopRecording() else onShutterTap()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.removeCallbacks(longPress)
                    if (quickTake) stopRecording()
                }
            }
            true
        }
    }

    private fun onShutterTap() {
        haptic(b.shutterButton)
        when {
            mode == Mode.VIDEO -> if (recording == null) startRecording() else stopRecording()
            countingDown -> Unit
            timerSeconds > 0 -> lifecycleScope.launch {
                countingDown = true
                b.countdown.visibility = View.VISIBLE
                for (s in timerSeconds downTo 1) {
                    b.countdown.text = s.toString()
                    b.countdown.rotation = uiRotation
                    delay(1000)
                }
                b.countdown.visibility = View.GONE
                countingDown = false
                takePhoto()
            }
            else -> takePhoto()
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (inFlight >= 3) return
        inFlight++
        b.processing.visibility = View.VISIBLE
        sound.play(MediaActionSound.SHUTTER_CLICK)
        b.shutterFlash.animate().cancel()
        b.shutterFlash.alpha = 1f
        b.shutterFlash.animate().alpha(0f).setDuration(220).start()

        val currentLut = lut!!
        val useHdr = hdr
        val ratio = format.ratio
        capture.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bytes = image.planes[0].buffer.let { buf -> ByteArray(buf.remaining()).also { buf.get(it) } }
                val rotation = image.imageInfo.rotationDegrees
                image.close()
                lifecycleScope.launch {
                    try {
                        val (uri, thumb) = withContext(Dispatchers.Default) {
                            val (upright, exif) = PhotoSaver.decodeUpright(bytes, rotation)
                            val framed = PhotoSaver.cropToRatio(upright, ratio)
                            if (framed !== upright) upright.recycle()
                            val out = ImageProcessor.process(framed, currentLut, useHdr)
                            framed.recycle()
                            val uri = withContext(Dispatchers.IO) {
                                PhotoSaver.save(this@MainActivity, out, exif, currentLut.style.label)
                            }
                            val thumb = Bitmap.createScaledBitmap(out, 160, 160 * out.height / out.width, true)
                            out.recycle()
                            uri to thumb
                        }
                        lastUri = uri
                        b.thumbnail.setImageBitmap(thumb)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Processing failed", t)
                        Toast.makeText(this@MainActivity, R.string.save_failed, Toast.LENGTH_SHORT).show()
                    } finally {
                        captureDone()
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Capture failed", exception)
                Toast.makeText(this@MainActivity, R.string.save_failed, Toast.LENGTH_SHORT).show()
                captureDone()
            }
        })
    }

    private fun captureDone() {
        inFlight--
        if (inFlight <= 0) {
            inFlight = 0
            b.processing.visibility = View.GONE
        }
    }

    // ---------------- Video ----------------

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "ILK_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/iLook")
        }
        val options = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
        val audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        recording = vc.output.prepareRecording(this, options)
            .apply { if (audio) withAudioEnabled() }
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Status -> {
                        val s = TimeUnit.NANOSECONDS.toSeconds(event.recordingStats.recordedDurationNanos)
                        b.recTimer.text = String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) {
                            Log.e(TAG, "Recording error ${event.error}", event.cause)
                            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
                        } else {
                            val uri = event.outputResults.outputUri
                            lastUri = uri
                            lifecycleScope.launch {
                                val thumb = withContext(Dispatchers.IO) {
                                    runCatching { contentResolver.loadThumbnail(uri, Size(160, 160), null) }.getOrNull()
                                }
                                thumb?.let { b.thumbnail.setImageBitmap(it) }
                            }
                        }
                    }
                }
            }
        b.recTimer.text = "00:00:00"
        refreshUi()
    }

    private fun stopRecording() {
        val r = recording ?: return
        recording = null
        quickTake = false
        r.stop()
        sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        refreshUi()
    }

    // ---------------- Modes & tray ----------------

    private fun setMode(m: Mode) {
        if (m == mode || recording != null || countingDown) return
        haptic(b.modeBar)
        mode = m
        if (m == Mode.VIDEO &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        closeTray()
        centerMode(animate = true)
        bindCamera()
    }

    private fun centerMode(animate: Boolean) {
        val target = if (mode == Mode.VIDEO) b.modeVideo else b.modePhoto
        val barCenter = b.modeBar.width / 2f
        val dx = barCenter - (target.left + target.width / 2f)
        if (animate) b.modeBar.animate().translationX(dx).setDuration(200).start() else b.modeBar.translationX = dx
    }

    private fun trayItemsForMode() =
        if (mode == Mode.VIDEO) listOf(TrayItem.FLASH, TrayItem.EXPOSURE, TrayItem.GRID)
        else TrayItem.entries.toList()

    private fun buildTray() {
        val icons = mapOf(
            TrayItem.FLASH to R.drawable.ic_flash_auto,
            TrayItem.FORMAT to R.drawable.ic_aspect,
            TrayItem.TIMER to R.drawable.ic_timer,
            TrayItem.EXPOSURE to R.drawable.ic_exposure,
            TrayItem.STYLES to R.drawable.ic_styles,
            TrayItem.GRID to R.drawable.ic_grid,
        )
        for (item in TrayItem.entries) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(10), 0, dp(10), 0)
            }
            val icon = ImageView(this).apply {
                setImageResource(icons.getValue(item))
                setBackgroundResource(R.drawable.tray_btn_bg)
                scaleType = ImageView.ScaleType.CENTER
            }
            val label = TextView(this).apply {
                setTextColor(getColor(R.color.white))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = Gravity.CENTER
                maxLines = 1
            }
            col.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
            col.addView(label, LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            col.setOnClickListener { haptic(it); onTrayItem(item) }
            b.trayList.addView(col, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
            trayViews[item] = icon to label
        }
    }

    private fun toggleTray() = if (b.tray.visibility == View.VISIBLE) closeTray() else openTray()

    private fun openTray() {
        if (recording != null) return
        val visible = trayItemsForMode()
        for ((item, views) in trayViews) (views.first.parent as View).visibility =
            if (item in visible) View.VISIBLE else View.GONE
        refreshTrayValues()
        b.modeBar.visibility = View.GONE
        b.tray.visibility = View.VISIBLE
        b.tray.alpha = 0f
        b.tray.animate().alpha(1f).setDuration(150).start()
        b.chevronButton.setImageResource(R.drawable.ic_chevron_down)
    }

    private fun closeTray() {
        openTrayItem = null
        b.optionsRow.visibility = View.INVISIBLE
        b.tray.visibility = View.GONE
        b.modeBar.visibility = if (recording == null) View.VISIBLE else View.INVISIBLE
        b.chevronButton.setImageResource(R.drawable.ic_chevron_up)
    }

    private fun onTrayItem(item: TrayItem) {
        when (item) {
            TrayItem.GRID -> {
                grid = !grid
                prefs.edit().putBoolean("grid", grid).apply()
                refreshUi()
                return
            }
            else -> Unit
        }
        if (openTrayItem == item) {
            openTrayItem = null
            b.optionsRow.visibility = View.INVISIBLE
            refreshTrayValues()
            return
        }
        openTrayItem = item
        b.optionsList.removeAllViews()
        b.exposureSeek.visibility = View.GONE
        b.optionsList.visibility = View.VISIBLE
        when (item) {
            TrayItem.FLASH -> if (mode == Mode.VIDEO) {
                addOptions(listOf("Off" to !torch, "On" to torch)) { i -> setTorch(i == 1) }
            } else {
                addOptions(
                    listOf(
                        "Auto" to (flashMode == ImageCapture.FLASH_MODE_AUTO),
                        "On" to (flashMode == ImageCapture.FLASH_MODE_ON),
                        "Off" to (flashMode == ImageCapture.FLASH_MODE_OFF),
                    ),
                ) { i -> setFlash(listOf(ImageCapture.FLASH_MODE_AUTO, ImageCapture.FLASH_MODE_ON, ImageCapture.FLASH_MODE_OFF)[i]) }
            }
            TrayItem.FORMAT -> addOptions(Format.entries.map { it.label to (it == format) }) { i -> setFormat(Format.entries[i]) }
            TrayItem.TIMER -> addOptions(
                listOf("Off" to (timerSeconds == 0), "3 s" to (timerSeconds == 3), "10 s" to (timerSeconds == 10)),
            ) { i ->
                timerSeconds = listOf(0, 3, 10)[i]
                prefs.edit().putInt("timer", timerSeconds).apply()
            }
            TrayItem.STYLES -> addOptions(Styles.ALL.map { it.label to (it == style) }) { i -> setStyle(Styles.ALL[i]) }
            TrayItem.EXPOSURE -> {
                b.optionsList.visibility = View.GONE
                val es = camera?.cameraInfo?.exposureState
                if (es != null && es.isExposureCompensationSupported) {
                    b.exposureSeek.max = es.exposureCompensationRange.upper - es.exposureCompensationRange.lower
                    b.exposureSeek.progress = es.exposureCompensationIndex - es.exposureCompensationRange.lower
                    b.exposureSeek.visibility = View.VISIBLE
                }
            }
            TrayItem.GRID -> Unit
        }
        b.optionsRow.visibility = View.VISIBLE
        refreshTrayValues()
    }

    private fun addOptions(options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
        val scroll = options.size > 4
        val container = if (scroll) {
            android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        } else null
        val row = if (scroll) LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL } else b.optionsList
        options.forEachIndexed { i, (text, selected) ->
            val tv = TextView(this).apply {
                this.text = text
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(4), dp(14), dp(4))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(getColor(if (selected) R.color.ios_yellow else R.color.white))
                if (selected) setBackgroundResource(R.drawable.option_selected_bg)
                setOnClickListener {
                    haptic(it)
                    onPick(i)
                    openTrayItem = null
                    onTrayItemReopen()
                }
            }
            row.addView(tv)
        }
        if (container != null) {
            container.addView(row)
            b.optionsList.addView(container, LinearLayout.LayoutParams(resources.displayMetrics.widthPixels, LinearLayout.LayoutParams.MATCH_PARENT))
        }
    }

    /** After a pick, collapse the options and refresh the tray labels. */
    private fun onTrayItemReopen() {
        b.optionsRow.visibility = View.INVISIBLE
        refreshTrayValues()
        refreshUi()
    }

    private fun cycleFlash() {
        if (mode == Mode.VIDEO) {
            setTorch(!torch)
        } else {
            setFlash(
                when (flashMode) {
                    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                    ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                    else -> ImageCapture.FLASH_MODE_AUTO
                },
            )
        }
    }

    private fun setFlash(m: Int) {
        flashMode = m
        imageCapture?.flashMode = m
        prefs.edit().putInt("flash", m).apply()
        refreshUi()
    }

    private fun setTorch(on: Boolean) {
        torch = on
        camera?.cameraControl?.enableTorch(on)
        refreshUi()
    }

    private fun setFormat(f: Format) {
        format = f
        prefs.edit().putInt("format", f.ordinal).apply()
        refreshUi()
    }

    private fun setStyle(s: PhotoStyle) {
        style = s
        prefs.edit().putString("style", s.id).apply()
        showInfo(s.label)
        lifecycleScope.launch {
            val newLut = withContext(Dispatchers.Default) { Lut3D(s) }
            lut = newLut
            processor?.setLut(newLut)
        }
    }

    // ---------------- UI state ----------------

    private fun refreshUi() {
        val recordingNow = recording != null
        b.shutterButton.setState(
            when {
                recordingNow -> ShutterButton.State.RECORDING
                mode == Mode.VIDEO -> ShutterButton.State.VIDEO
                else -> ShutterButton.State.PHOTO
            },
        )
        b.modeVideo.setTextColor(getColor(if (mode == Mode.VIDEO) R.color.ios_yellow else R.color.white))
        b.modePhoto.setTextColor(getColor(if (mode == Mode.PHOTO) R.color.ios_yellow else R.color.white))

        val viewRatio = if (mode == Mode.VIDEO) "9:16" else format.viewRatio
        val lp = b.previewView.layoutParams as ConstraintLayout.LayoutParams
        if (lp.dimensionRatio != viewRatio) {
            lp.dimensionRatio = viewRatio
            b.previewView.layoutParams = lp
        }
        b.grid.visibility = if (grid) View.VISIBLE else View.GONE

        b.flashButton.setImageResource(
            if (mode == Mode.VIDEO) {
                if (torch) R.drawable.ic_flash_on else R.drawable.ic_flash_off
            } else {
                when (flashMode) {
                    ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
                    ImageCapture.FLASH_MODE_OFF -> R.drawable.ic_flash_off
                    else -> R.drawable.ic_flash_auto
                }
            },
        )
        val flashOn = if (mode == Mode.VIDEO) torch else flashMode == ImageCapture.FLASH_MODE_ON
        b.flashButton.setColorFilter(getColor(if (flashOn) R.color.ios_yellow else R.color.white))
        b.hdrButton.setTextColor(getColor(if (hdr) R.color.ios_yellow else R.color.white))
        b.hdrButton.visibility = if (mode == Mode.PHOTO && !recordingNow) View.VISIBLE else View.INVISIBLE

        // While recording: timer replaces the chevron; modes, tray and camera flip are hidden.
        b.recTimer.visibility = if (recordingNow) View.VISIBLE else View.GONE
        b.chevronButton.visibility = if (recordingNow) View.GONE else View.VISIBLE
        if (recordingNow) closeTray()
        b.modeBar.visibility = when {
            recordingNow -> View.INVISIBLE
            b.tray.visibility == View.VISIBLE -> View.GONE
            else -> View.VISIBLE
        }
        b.flipButton.visibility = if (recordingNow) View.INVISIBLE else View.VISIBLE
        b.zoomBar.visibility = if (lensFacing == CameraSelector.LENS_FACING_BACK) View.VISIBLE else View.GONE
        refreshTrayValues()
    }

    private fun refreshTrayValues() {
        fun set(item: TrayItem, text: String, active: Boolean) {
            val (icon, label) = trayViews[item] ?: return
            label.text = text
            val c = getColor(if (active || openTrayItem == item) R.color.ios_yellow else R.color.white)
            label.setTextColor(c)
            icon.setColorFilter(c)
        }
        if (mode == Mode.VIDEO) {
            set(TrayItem.FLASH, if (torch) "On" else "Off", torch)
            trayViews[TrayItem.FLASH]?.first?.setImageResource(if (torch) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        } else {
            set(
                TrayItem.FLASH,
                when (flashMode) {
                    ImageCapture.FLASH_MODE_ON -> "On"
                    ImageCapture.FLASH_MODE_OFF -> "Off"
                    else -> "Auto"
                },
                flashMode == ImageCapture.FLASH_MODE_ON,
            )
            trayViews[TrayItem.FLASH]?.first?.setImageResource(
                when (flashMode) {
                    ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
                    ImageCapture.FLASH_MODE_OFF -> R.drawable.ic_flash_off
                    else -> R.drawable.ic_flash_auto
                },
            )
        }
        set(TrayItem.FORMAT, format.label, false)
        set(TrayItem.TIMER, if (timerSeconds == 0) "Off" else "$timerSeconds s", timerSeconds > 0)
        val es = camera?.cameraInfo?.exposureState
        val ev = if (es != null) es.exposureCompensationIndex * es.exposureCompensationStep.toFloat() else 0f
        set(TrayItem.EXPOSURE, String.format(Locale.US, "%+.1f", ev), abs(ev) > 0.01f)
        set(TrayItem.STYLES, style.label, style != Styles.ALL[0])
        set(TrayItem.GRID, if (grid) "On" else "Off", grid)
    }

    private fun rotateUi(deg: Float) {
        uiRotation = deg
        val views = listOf<View>(b.flashButton, b.hdrButton, b.thumbnail, b.flipButton, b.countdown) +
            zoomViews + trayViews.values.map { it.first }
        for (v in views) {
            // Take the short way round.
            var target = deg
            val cur = v.rotation % 360
            if (target - cur > 180) target -= 360
            if (cur - target > 180) target += 360
            v.animate().rotation(target).setDuration(250).start()
        }
    }

    private fun buildZoomBar() {
        val size = dp(36)
        for ((ratio, label) in zoomStops) {
            val tv = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.white))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setBackgroundResource(R.drawable.zoom_dot)
                setOnClickListener { haptic(it); setZoom(ratio) }
            }
            val lp = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(3); marginEnd = dp(3) }
            b.zoomBar.addView(tv, lp)
            zoomViews += tv
        }
    }

    private fun setZoom(ratio: Float) {
        val cam = camera ?: return
        val zs = cam.cameraInfo.zoomState.value ?: return
        cam.cameraControl.setZoomRatio(ratio.coerceIn(zs.minZoomRatio, zs.maxZoomRatio))
    }

    private fun updateZoomBar(current: Float) {
        val minZoom = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
        // Highlight the stop closest (from below) to the current ratio.
        var active = 0
        for ((i, stop) in zoomStops.withIndex()) {
            if (current + 0.01f >= maxOf(stop.first, if (i == 0) minZoom else stop.first)) active = i
        }
        for ((i, tv) in zoomViews.withIndex()) {
            val isActive = i == active
            tv.setTextColor(getColor(if (isActive) R.color.ios_yellow else R.color.white))
            tv.text = if (isActive) formatZoom(current) else zoomStops[i].second
            val s = if (isActive) 1.15f else 0.85f
            tv.scaleX = s; tv.scaleY = s
        }
    }

    private fun formatZoom(z: Float): String {
        // Show the iPhone-equivalent number (S25's 0.6x ultra-wide is presented as .5).
        val shown = if (z < 1f) 0.5f else z
        return if (shown < 1f) ".5×"
        else if (abs(shown - shown.roundToInt()) < 0.05f) "${shown.roundToInt()}×"
        else String.format(Locale.US, "%.1f×", shown)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val zs = cam.cameraInfo.zoomState.value ?: return false
                cam.cameraControl.setZoomRatio(
                    (zs.zoomRatio * detector.scaleFactor).coerceIn(zs.minZoomRatio, zs.maxZoomRatio),
                )
                return true
            }
        })
        // Horizontal swipe switches mode, like on iPhone.
        val fling = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || abs(vx) < abs(vy) * 1.5f || abs(e2.x - e1.x) < dp(60)) return false
                setMode(if (vx > 0) Mode.VIDEO else Mode.PHOTO)
                return true
            }
        })

        var downX = 0f
        var downY = 0f
        var dragging = false
        var startEvIndex = 0
        b.previewView.setOnTouchListener { _, ev ->
            scale.onTouchEvent(ev)
            if (ev.pointerCount > 1 || scale.isInProgress) {
                dragging = true
                return@setOnTouchListener true
            }
            if (!b.focusView.isShowing && fling.onTouchEvent(ev)) {
                dragging = true
                return@setOnTouchListener true
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; dragging = false
                    startEvIndex = camera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = downY - ev.y
                    if (!dragging && abs(dy) > dp(12) && b.focusView.isShowing) dragging = true
                    if (dragging && b.focusView.isShowing) adjustExposure(startEvIndex, dy)
                }
                MotionEvent.ACTION_UP -> if (!dragging && abs(ev.x - downX) < dp(10) && abs(ev.y - downY) < dp(10)) {
                    if (b.tray.visibility == View.VISIBLE) closeTray() else focusAt(ev.x, ev.y)
                }
            }
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        b.focusView.show(x, y)
        cam.cameraControl.setExposureCompensationIndex(0)
        val point = b.previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(5, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    private fun adjustExposure(startIndex: Int, dy: Float) {
        val cam = camera ?: return
        val es = cam.cameraInfo.exposureState
        if (!es.isExposureCompensationSupported) return
        val range = es.exposureCompensationRange
        val step = es.exposureCompensationStep.toFloat()
        // ~2 EV across 300dp of drag.
        val evDelta = dy / dp(150)
        val idx = (startIndex + (evDelta / step).roundToInt()).coerceIn(range.lower, range.upper)
        if (idx != es.exposureCompensationIndex) cam.cameraControl.setExposureCompensationIndex(idx)
        val ev = idx * step
        b.focusView.setEv(ev / 2f)
        showInfo(String.format(Locale.US, "☀ %+.1f", ev))
    }

    private val hideInfo = Runnable { b.infoLabel.visibility = View.GONE }

    private fun showInfo(text: String) {
        b.infoLabel.text = text
        b.infoLabel.visibility = View.VISIBLE
        b.infoLabel.removeCallbacks(hideInfo)
        b.infoLabel.postDelayed(hideInfo, 1500)
    }

    private fun openLast() {
        val uri = lastUri ?: return
        startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri) ?: "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    private fun haptic(v: View) = v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val TAG = "iLook"
    }
}
