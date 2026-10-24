package com.vindovinou.ilook

import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Person/background separation (ML Kit selfie segmentation, on device) used for the Portrait
 * and Cinematic background blur.
 */
object PersonSegmenter {

    /** Foreground confidence, one float per pixel, upright. */
    class FloatMask(val values: FloatArray, val width: Int, val height: Int)

    private val photoSegmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .enableRawSizeMask()
                .build(),
        )
    }

    /** Blocking; call off the main thread. [bitmap] must be upright. */
    fun segment(bitmap: Bitmap): FloatMask {
        // The model works at a small size anyway; feed it a downscaled copy.
        val scale = 1024f / maxOf(bitmap.width, bitmap.height)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        val mask = Tasks.await(photoSegmenter.process(InputImage.fromBitmap(small, 0)))
        if (small !== bitmap) small.recycle()
        return toFloats(mask)
    }

    private fun toFloats(mask: SegmentationMask): FloatMask {
        val buf = mask.buffer.order(ByteOrder.nativeOrder())
        buf.rewind()
        val values = FloatArray(mask.width * mask.height)
        buf.asFloatBuffer().get(values)
        return FloatMask(values, mask.width, mask.height)
    }

    /** Live analyzer feeding upright byte masks to [onMask] (on the analysis thread). */
    class Live(private val onMask: (LutSurfaceProcessor.Mask) -> Unit) : ImageAnalysis.Analyzer {
        private val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                .enableRawSizeMask()
                .build(),
        )

        @SuppressLint("UnsafeOptInUsageError")
        override fun analyze(image: ImageProxy) {
            val media = image.image
            if (media == null) {
                image.close()
                return
            }
            val rotation = image.imageInfo.rotationDegrees
            segmenter.process(InputImage.fromMediaImage(media, rotation))
                .addOnSuccessListener { m ->
                    val f = toFloats(m)
                    val bytes = ByteBuffer.allocateDirect(f.values.size)
                    for (v in f.values) bytes.put((v * 255f).toInt().coerceIn(0, 255).toByte())
                    bytes.rewind()
                    onMask(LutSurfaceProcessor.Mask(bytes, f.width, f.height, rotation))
                }
                .addOnCompleteListener { image.close() }
        }

        fun close() = segmenter.close()
    }
}
