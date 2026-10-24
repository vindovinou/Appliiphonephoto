package com.vindovinou.ilook

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PhotoSaver {

    private const val JPEG_QUALITY = 95

    private val COPIED_TAGS = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
    )

    /** Decodes the camera JPEG and bakes its EXIF orientation into the pixels. */
    fun decodeUpright(jpeg: ByteArray, fallbackRotation: Int): Pair<Bitmap, ExifInterface> {
        val exif = ExifInterface(ByteArrayInputStream(jpeg))
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
        val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        val hasExifOrientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) != 0
        val rotation = if (hasExifOrientation) exif.rotationDegrees else fallbackRotation
        val flip = hasExifOrientation && exif.isFlipped
        if (rotation == 0 && !flip) return raw to exif
        val m = Matrix().apply {
            postRotate(rotation.toFloat())
            if (flip) postScale(-1f, 1f)
        }
        val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        raw.recycle()
        return upright to exif
    }

    fun save(context: Context, bitmap: Bitmap, sourceExif: ExifInterface?, styleLabel: String): Uri {
        val name = "ILK_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/iLook")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            resolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                if (sourceExif != null) {
                    for (tag in COPIED_TAGS) sourceExif.getAttribute(tag)?.let { exif.setAttribute(tag, it) }
                }
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "iLook Camera ($styleLabel)")
                exif.saveAttributes()
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
