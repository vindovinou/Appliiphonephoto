package com.vindovinou.ilook

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.vindovinou.ilook.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("ilook", MODE_PRIVATE) }

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var effect: LutPreviewEffect? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_AUTO
    private var hdr = true
    private var style: PhotoStyle = Styles.ALL[0]
    private var lut: Lut3D? = null
    private var lastUri: Uri? = null
    private var inFlight = 0

    /** iPhone 17 Pro Max lens buttons. ".5" maps to the S25 Ultra's widest available (0.6x). */
    private val zoomStops = listOf(0.5f to ".5", 1f to "1×", 2f to "2", 4f to "4", 8f to "8")
    private val zoomViews = mutableListOf<TextView>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        style = Styles.byId(prefs.getString("style", null))
        hdr = prefs.getBoolean("hdr", true)
        flashMode = prefs.getInt("flash", ImageCapture.FLASH_MODE_AUTO)
        lut = Lut3D(style)

        buildZoomBar()
        updateTopBar()
        b.shutterButton.setOnClickListener { takePhoto() }
        b.flipButton.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
            else CameraSelector.LENS_FACING_BACK
            b.flipButton.animate().rotationBy(180f).setDuration(250).start()
            bindCamera()
        }
        b.flashButton.setOnClickListener {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                else -> ImageCapture.FLASH_MODE_AUTO
            }
            imageCapture?.flashMode = flashMode
            prefs.edit().putInt("flash", flashMode).apply()
            updateTopBar()
        }
        b.hdrButton.setOnClickListener {
            hdr = !hdr
            prefs.edit().putBoolean("hdr", hdr).apply()
            updateTopBar()
            showInfo(if (hdr) "Smart HDR activé" else "Smart HDR désactivé")
        }
        b.styleButton.setOnClickListener { pickStyle() }
        b.thumbnail.setOnClickListener { openLast() }
        setupGestures()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        effect?.release()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Volume buttons fire the shutter, like on iPhone.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event?.repeatCount == 0) takePhoto()
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
    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val ratio43 = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)

        val preview = Preview.Builder()
            .setResolutionSelector(ratio43.build())
            .build()
            .also { it.surfaceProvider = b.previewView.surfaceProvider }

        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flashMode)
            .setJpegQuality(100)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build(),
            )
        // Ask the ISP for clean, un-oversharpened frames; our own pipeline adds Apple-like detail.
        Camera2Interop.Extender(captureBuilder)
            .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        val capture = captureBuilder.build()

        val fx = effect ?: LutPreviewEffect.create(lut!!).also { effect = it }
        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(capture)
            .addEffect(fx)
            .build()

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, group)
            imageCapture = capture
        } catch (e: Exception) {
            Log.e(TAG, "Bind with effect failed, falling back to plain preview", e)
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, preview, capture)
            imageCapture = capture
        }
        b.zoomBar.visibility = if (lensFacing == CameraSelector.LENS_FACING_BACK) View.VISIBLE else View.GONE
        camera?.cameraInfo?.zoomState?.observe(this) { updateZoomBar(it.zoomRatio) }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (inFlight >= 3) return
        inFlight++
        b.processing.visibility = View.VISIBLE
        b.shutterFlash.animate().cancel()
        b.shutterFlash.alpha = 1f
        b.shutterFlash.animate().alpha(0f).setDuration(220).start()

        val currentLut = lut!!
        val useHdr = hdr
        capture.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bytes = image.planes[0].buffer.let { buf -> ByteArray(buf.remaining()).also { buf.get(it) } }
                val rotation = image.imageInfo.rotationDegrees
                image.close()
                lifecycleScope.launch {
                    try {
                        val (uri, thumb) = withContext(Dispatchers.Default) {
                            val (upright, exif) = PhotoSaver.decodeUpright(bytes, rotation)
                            val out = ImageProcessor.process(upright, currentLut, useHdr)
                            upright.recycle()
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

    // ---------------- UI ----------------

    private fun updateTopBar() {
        b.flashButton.text = when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> "⚡ On"
            ImageCapture.FLASH_MODE_OFF -> "⚡ Off"
            else -> "⚡ Auto"
        }
        b.flashButton.setTextColor(
            if (flashMode == ImageCapture.FLASH_MODE_ON) getColor(R.color.ios_yellow) else getColor(R.color.white),
        )
        b.hdrButton.setTextColor(if (hdr) getColor(R.color.ios_yellow) else getColor(R.color.white))
        b.hdrButton.paintFlags = if (hdr) b.hdrButton.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
        else b.hdrButton.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        b.styleButton.text = style.label
    }

    private fun pickStyle() {
        val labels = Styles.ALL.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Style photographique")
            .setSingleChoiceItems(labels, Styles.ALL.indexOf(style)) { d, which ->
                style = Styles.ALL[which]
                prefs.edit().putString("style", style.id).apply()
                updateTopBar()
                d.dismiss()
                lifecycleScope.launch {
                    val newLut = withContext(Dispatchers.Default) { Lut3D(style) }
                    lut = newLut
                    effect?.setLut(newLut)
                }
            }
            .show()
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
                setOnClickListener { setZoom(ratio) }
            }
            val lp = android.widget.LinearLayout.LayoutParams(size, size).apply { marginStart = dp(3); marginEnd = dp(3) }
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
        else String.format(java.util.Locale.US, "%.1f×", shown)
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
                    focusAt(ev.x, ev.y)
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
        showInfo(String.format(java.util.Locale.US, "☀ %+.1f", ev))
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
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val TAG = "iLook"
    }
}
