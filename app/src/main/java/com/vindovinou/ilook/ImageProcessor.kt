package com.vindovinou.ilook

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Full resolution pipeline applied to every captured photo:
 *  1. Smart-HDR-like local tone mapping (shadow lift / highlight pull on a blurred luminance base,
 *     so local contrast and texture are preserved).
 *  2. Global iPhone colour science through the 3D LUT.
 *  3. Gentle, small-radius luminance sharpening (the capture is requested without the
 *     Samsung ISP edge enhancement, which is much harsher than Apple's).
 */
object ImageProcessor {

    private const val CELL = 16
    private const val SHARPEN_AMOUNT = 0.55f

    private val srgbToLin = FloatArray(256) { ColorScience.srgbToLinear(it / 255f) }
    private val linToSrgb = IntArray(4096) {
        (ColorScience.linearToSrgb(it / 4095f) * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    suspend fun process(src: Bitmap, lut: Lut3D, hdr: Boolean): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val gain = if (hdr) buildGainField(px, w, h, lut.style) else null
        colour(px, w, h, lut, gain)
        sharpen(px, w, h)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private suspend fun parallelRows(h: Int, block: (y0: Int, y1: Int) -> Unit) = coroutineScope {
        val n = Runtime.getRuntime().availableProcessors().coerceAtLeast(2) * 2
        val step = (h + n - 1) / n
        (0 until n).map { i ->
            async(Dispatchers.Default) {
                val y0 = i * step
                val y1 = minOf(h, y0 + step)
                if (y0 < y1) block(y0, y1)
            }
        }.awaitAll()
    }

    // ---------------- Portrait ----------------

    /**
     * Portrait-mode background blur: blends [src] with a heavily blurred copy wherever
     * [mask] says "background". [strength] scales the blur radius (1 = iPhone-like f/4.5 look).
     */
    suspend fun portrait(src: Bitmap, mask: PersonSegmenter.FloatMask, strength: Float = 1f): Bitmap {
        val w = src.width
        val h = src.height
        // Blur at 1/8 resolution, then upsample bilinearly while blending.
        val sw = maxOf(8, w / 8)
        val sh = maxOf(8, h / 8)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val bg = IntArray(sw * sh)
        small.getPixels(bg, 0, sw, 0, 0, sw, sh)
        small.recycle()
        val r = maxOf(2, (maxOf(sw, sh) / 70f * strength).toInt())
        repeat(3) { boxBlurArgb(bg, sw, sh, r) }

        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val mw = mask.width
        val mh = mask.height
        parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val my = (y + 0.5f) * mh / h - 0.5f
                val sy = (y + 0.5f) * sh / h - 0.5f
                for (x in 0 until w) {
                    val m = bilinear(mask.values, mw, mh, (x + 0.5f) * mw / w - 0.5f, my)
                    val t = ((m - 0.35f) / 0.4f).coerceIn(0f, 1f)
                    val fg = t * t * (3 - 2 * t)
                    if (fg >= 0.999f) continue
                    val b = sampleArgb(bg, sw, sh, (x + 0.5f) * sw / w - 0.5f, sy)
                    val c = px[y * w + x]
                    val k = fg
                    val rr = (((c shr 16) and 0xFF) * k + ((b shr 16) and 0xFF) * (1 - k)).toInt()
                    val gg = (((c shr 8) and 0xFF) * k + ((b shr 8) and 0xFF) * (1 - k)).toInt()
                    val bb = ((c and 0xFF) * k + (b and 0xFF) * (1 - k)).toInt()
                    px[y * w + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun bilinear(a: FloatArray, w: Int, h: Int, fx: Float, fy: Float): Float {
        val x = fx.coerceIn(0f, (w - 1).toFloat())
        val y = fy.coerceIn(0f, (h - 1).toFloat())
        val x0 = x.toInt().coerceAtMost(w - 2).coerceAtLeast(0)
        val y0 = y.toInt().coerceAtMost(h - 2).coerceAtLeast(0)
        val x1 = minOf(x0 + 1, w - 1)
        val y1 = minOf(y0 + 1, h - 1)
        val tx = x - x0
        val ty = y - y0
        val top = a[y0 * w + x0] + (a[y0 * w + x1] - a[y0 * w + x0]) * tx
        val bot = a[y1 * w + x0] + (a[y1 * w + x1] - a[y1 * w + x0]) * tx
        return top + (bot - top) * ty
    }

    private fun sampleArgb(a: IntArray, w: Int, h: Int, fx: Float, fy: Float): Int {
        val x = fx.coerceIn(0f, (w - 1).toFloat())
        val y = fy.coerceIn(0f, (h - 1).toFloat())
        val x0 = x.toInt().coerceAtMost(w - 2).coerceAtLeast(0)
        val y0 = y.toInt().coerceAtMost(h - 2).coerceAtLeast(0)
        val tx = x - x0
        val ty = y - y0
        val c00 = a[y0 * w + x0]
        val c10 = a[y0 * w + x0 + 1]
        val c01 = a[(y0 + 1) * w + x0]
        val c11 = a[(y0 + 1) * w + x0 + 1]
        var out = 0xFF shl 24
        for (shift in intArrayOf(16, 8, 0)) {
            val top = ((c00 shr shift) and 0xFF) + (((c10 shr shift) and 0xFF) - ((c00 shr shift) and 0xFF)) * tx
            val bot = ((c01 shr shift) and 0xFF) + (((c11 shr shift) and 0xFF) - ((c01 shr shift) and 0xFF)) * tx
            out = out or ((top + (bot - top) * ty).toInt().coerceIn(0, 255) shl shift)
        }
        return out
    }

    private fun boxBlurArgb(a: IntArray, w: Int, h: Int, r: Int) {
        for (horizontal in booleanArrayOf(true, false)) {
            val len = if (horizontal) w else h
            val lines = if (horizontal) h else w
            val tmp = IntArray(len)
            for (line in 0 until lines) {
                fun at(i: Int) = if (horizontal) line * w + i else i * w + line
                var sr = 0; var sg = 0; var sb = 0
                for (i in -r..r) {
                    val c = a[at(i.coerceIn(0, len - 1))]
                    sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
                }
                val n = 2 * r + 1
                for (i in 0 until len) {
                    tmp[i] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                    val add = a[at((i + r + 1).coerceAtMost(len - 1))]
                    val rem = a[at((i - r).coerceAtLeast(0))]
                    sr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                    sg += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                    sb += (add and 0xFF) - (rem and 0xFF)
                }
                for (i in 0 until len) a[at(i)] = tmp[i]
            }
        }
    }

    // ---------------- Local tone mapping ----------------

    private class GainField(val cw: Int, val ch: Int, val gain: FloatArray)

    private suspend fun buildGainField(px: IntArray, w: Int, h: Int, style: PhotoStyle): GainField {
        val cw = (w + CELL - 1) / CELL
        val ch = (h + CELL - 1) / CELL
        val base = FloatArray(cw * ch)

        // Mean log2 luminance per cell (sampled every other pixel, plenty for a base layer).
        parallelRows(ch) { cy0, cy1 ->
            for (cy in cy0 until cy1) for (cx in 0 until cw) {
                var sum = 0f
                var cnt = 0
                val yEnd = minOf(h, (cy + 1) * CELL)
                val xEnd = minOf(w, (cx + 1) * CELL)
                var y = cy * CELL
                while (y < yEnd) {
                    var x = cx * CELL
                    while (x < xEnd) {
                        val c = px[y * w + x]
                        val lum = 0.2126f * srgbToLin[(c shr 16) and 0xFF] +
                            0.7152f * srgbToLin[(c shr 8) and 0xFF] +
                            0.0722f * srgbToLin[c and 0xFF]
                        sum += log2(lum + 1e-4f)
                        cnt++
                        x += 2
                    }
                    y += 2
                }
                base[cy * cw + cx] = sum / cnt
            }
        }

        // Wide Gaussian-ish blur (3 box passes) so the base only carries large-scale lighting.
        val radius = maxOf(2, maxOf(cw, ch) / 40)
        repeat(3) {
            boxBlur(base, cw, ch, radius, horizontal = true)
            boxBlur(base, cw, ch, radius, horizontal = false)
        }

        val mid = log2(0.18f)
        val gain = FloatArray(base.size)
        for (i in base.indices) {
            val d = base[i] - mid
            val lift = (style.shadowLift * softRelu(-(d + 1.0f)) * 0.55f).coerceAtMost(1.6f)
            val pull = (style.highlightPull * softRelu(d - 0.9f) * 0.45f).coerceAtMost(1.0f)
            gain[i] = 2f.pow(lift - pull)
        }
        return GainField(cw, ch, gain)
    }

    private fun softRelu(x: Float) = (x + sqrt(x * x + 0.04f)) * 0.5f

    private fun log2(x: Float) = (ln(x.toDouble()) / ln(2.0)).toFloat()

    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val tmp = FloatArray(len)
        for (line in 0 until lines) {
            fun at(i: Int) = if (horizontal) line * w + i else i * w + line
            var acc = 0f
            for (i in -r..r) acc += a[at(i.coerceIn(0, len - 1))]
            for (i in 0 until len) {
                tmp[i] = acc / (2 * r + 1)
                acc += a[at((i + r + 1).coerceAtMost(len - 1))] - a[at((i - r).coerceAtLeast(0))]
            }
            for (i in 0 until len) a[at(i)] = tmp[i]
        }
    }

    // ---------------- Colour ----------------

    private suspend fun colour(px: IntArray, w: Int, h: Int, lut: Lut3D, gf: GainField?) {
        parallelRows(h) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                // Bilinear position in the gain grid (cell centres).
                val gy = ((y + 0.5f) / CELL - 0.5f)
                for (x in 0 until w) {
                    val c = px[row + x]
                    var r = (c shr 16) and 0xFF
                    var g = (c shr 8) and 0xFF
                    var b = c and 0xFF
                    if (gf != null) {
                        val k = sampleGain(gf, (x + 0.5f) / CELL - 0.5f, gy)
                        r = linToSrgb[((srgbToLin[r] * k) * 4095f).toInt().coerceIn(0, 4095)]
                        g = linToSrgb[((srgbToLin[g] * k) * 4095f).toInt().coerceIn(0, 4095)]
                        b = linToSrgb[((srgbToLin[b] * k) * 4095f).toInt().coerceIn(0, 4095)]
                    }
                    px[row + x] = lut.apply(r, g, b)
                }
            }
        }
    }

    private fun sampleGain(gf: GainField, fx: Float, fy: Float): Float {
        val x = fx.coerceIn(0f, (gf.cw - 1).toFloat())
        val y = fy.coerceIn(0f, (gf.ch - 1).toFloat())
        val x0 = minOf(x.toInt(), gf.cw - 2).coerceAtLeast(0)
        val y0 = minOf(y.toInt(), gf.ch - 2).coerceAtLeast(0)
        val x1 = minOf(x0 + 1, gf.cw - 1)
        val y1 = minOf(y0 + 1, gf.ch - 1)
        val tx = x - x0
        val ty = y - y0
        val g = gf.gain
        val a = g[y0 * gf.cw + x0] + (g[y0 * gf.cw + x1] - g[y0 * gf.cw + x0]) * tx
        val b = g[y1 * gf.cw + x0] + (g[y1 * gf.cw + x1] - g[y1 * gf.cw + x0]) * tx
        return a + (b - a) * ty
    }

    // ---------------- Sharpening ----------------

    private suspend fun sharpen(px: IntArray, w: Int, h: Int) {
        val luma = ByteArray(w * h)
        parallelRows(h) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                val c = px[i]
                luma[i] = ((((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8).toByte()
            }
        }
        val amount = (SHARPEN_AMOUNT * 256).toInt()
        parallelRows(h) { y0, y1 ->
            for (y in maxOf(1, y0) until minOf(h - 1, y1)) {
                val row = y * w
                for (x in 1 until w - 1) {
                    val i = row + x
                    fun l(j: Int) = luma[j].toInt() and 0xFF
                    // 3x3 binomial blur.
                    val blur = (l(i - w - 1) + 2 * l(i - w) + l(i - w + 1) +
                        2 * l(i - 1) + 4 * l(i) + 2 * l(i + 1) +
                        l(i + w - 1) + 2 * l(i + w) + l(i + w + 1)) shr 4
                    var d = l(i) - blur
                    // Ignore tiny differences so flat areas (skies, skin) don't get crunchy noise.
                    d = when {
                        d > 2 -> d - 2
                        d < -2 -> d + 2
                        else -> 0
                    }
                    if (d == 0) continue
                    val delta = (d * amount) shr 8
                    val c = px[i]
                    val r = (((c shr 16) and 0xFF) + delta).coerceIn(0, 255)
                    val g = (((c shr 8) and 0xFF) + delta).coerceIn(0, 255)
                    val b = ((c and 0xFF) + delta).coerceIn(0, 255)
                    px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
    }
}
