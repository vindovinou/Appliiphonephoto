package com.vindovinou.ilook

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Time-lapse: grabs one analysis frame every [intervalMs], applies the iPhone look and encodes
 * the frames back to back at 30 fps (2 frames/s captured -> 15x faster playback).
 */
class TimeLapseRecorder(
    private val context: Context,
    private val lutProvider: () -> Lut3D,
    private val intervalMs: Long = 500,
) : ImageAnalysis.Analyzer {

    private val thread = HandlerThread("TimeLapseEnc").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var running = false
    private var lastFrameAt = 0L
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var track = -1
    private var frameIndex = 0L
    private var outWidth = 0
    private var outHeight = 0
    private var landscape = false
    private var onFinished: ((Uri?) -> Unit)? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    val isRecording get() = running

    /** [landscape] picks the output orientation, from how the phone is held at start. */
    fun start(landscape: Boolean) {
        if (running) return
        this.landscape = landscape
        frameIndex = 0
        lastFrameAt = 0
        running = true
    }

    fun stop(onDone: (Uri?) -> Unit) {
        if (!running) {
            onDone(null)
            return
        }
        running = false
        handler.post {
            onFinished = onDone
            val c = codec
            if (c == null) {
                finish(null)
            } else {
                c.signalEndOfInputStream()
            }
        }
    }

    fun release() {
        running = false
        thread.quitSafely()
    }

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (!running || now - lastFrameAt < intervalMs) {
            image.close()
            return
        }
        lastFrameAt = now
        val bitmap = try {
            val raw = image.toBitmap()
            val rot = image.imageInfo.rotationDegrees
            if (rot == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        } finally {
            image.close()
        }
        // Colour on the analysis thread; encoding on the encoder thread.
        val lut = lutProvider()
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            px[i] = lut.apply((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        }
        val graded = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        bitmap.recycle()
        handler.post { encode(graded) }
    }

    private fun encode(frame: Bitmap) {
        // A frame can still arrive after stop(); never let it open a new file.
        if (!running) {
            frame.recycle()
            return
        }
        try {
            if (codec == null) setup(frame)
            val surface = inputSurface ?: return
            val canvas = surface.lockHardwareCanvas()
            try {
                // Centre-crop the frame into the output.
                val fw = frame.width.toFloat()
                val fh = frame.height.toFloat()
                val scale = maxOf(outWidth / fw, outHeight / fh)
                val cw = (outWidth / scale).toInt()
                val ch = (outHeight / scale).toInt()
                val src = Rect(((fw - cw) / 2).toInt(), ((fh - ch) / 2).toInt(), ((fw + cw) / 2).toInt(), ((fh + ch) / 2).toInt())
                canvas.drawBitmap(frame, src, Rect(0, 0, outWidth, outHeight), paint)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encode failed", e)
        } finally {
            frame.recycle()
        }
    }

    private fun setup(first: Bitmap) {
        // 1080p, oriented like the phone was held when recording started.
        outWidth = if (landscape) 1920 else 1080
        outHeight = if (landscape) 1080 else 1920
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outWidth, outHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 16_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val m = muxer
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && m != null && track >= 0) {
                    // Frames are captured sparsely; re-time them back to back for fast playback.
                    info.presentationTimeUs = frameIndex++ * 1_000_000L / FPS
                    m.writeSampleData(track, codec.getOutputBuffer(index)!!, info)
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) finish(uri)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Codec error", e)
                finish(null)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                val m = muxer ?: return
                track = m.addTrack(format)
                m.start()
            }
        }, handler)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = c.createInputSurface()

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "ILK_TL_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/iLook")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        pfd = context.contentResolver.openFileDescriptor(uri!!, "rw")
        muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        c.start()
        codec = c
    }

    private fun finish(result: Uri?) {
        var saved: Uri? = result
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        codec?.release()
        codec = null
        inputSurface?.release()
        inputSurface = null
        try {
            if (track >= 0) muxer?.stop() else saved = null
        } catch (e: Exception) {
            Log.e(TAG, "Muxer stop failed", e)
            saved = null
        }
        muxer?.release()
        muxer = null
        pfd?.close()
        pfd = null
        track = -1
        uri?.let { u ->
            if (saved != null) {
                context.contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } else {
                context.contentResolver.delete(u, null, null)
            }
        }
        uri = null
        val cb = onFinished
        onFinished = null
        cb?.let { done -> android.os.Handler(context.mainLooper).post { done(saved) } }
    }

    companion object {
        private const val TAG = "TimeLapse"
        private const val FPS = 30
    }
}
