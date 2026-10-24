package com.vindovinou.ilook

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * A "Photographic Style" in the iPhone sense: a set of global colour/tone parameters.
 *
 * The values are tuned to move a Samsung (S25 Ultra) ISP output toward the iPhone 17 Pro look,
 * checked against real 17 Pro Max shots: bright and airy exposure, near-neutral slightly warm
 * whites held just below clipping, rich yellow-leaning greens, warm saturated wood/skin tones,
 * deeper skies than Samsung's cyan, lifted shadows and a gentle S-curve.
 */
data class PhotoStyle(
    val id: String,
    val label: String,
    /** Global exposure offset in EV, applied in linear light. */
    val exposureEv: Float,
    /** >0 warmer (amber), <0 cooler (blue). */
    val warmth: Float,
    /** >0 magenta, <0 green. */
    val tint: Float,
    /** Global chroma multiplier in OKLCh. */
    val chroma: Float,
    /** Midtone contrast of the S-curve (0 = linear). */
    val contrast: Float,
    /** Black level lift in Oklab L (0..~0.03). */
    val blackLift: Float,
    /** How strongly local tone mapping lifts shadows (Smart-HDR-like). */
    val shadowLift: Float,
    /** How strongly local tone mapping pulls highlights down. */
    val highlightPull: Float,
    val mono: Boolean = false,
)

object Styles {
    val ALL = listOf(
        PhotoStyle("standard", "Standard", -0.04f, 0.50f, 0.03f, 1.00f, 0.10f, 0.025f, 0.45f, 0.35f),
        PhotoStyle("rich", "Contraste riche", -0.12f, 0.45f, 0.03f, 1.04f, 0.28f, 0.005f, 0.25f, 0.30f),
        PhotoStyle("vibrant", "Éclatant", -0.04f, 0.50f, 0.03f, 1.14f, 0.12f, 0.020f, 0.45f, 0.35f),
        PhotoStyle("natural", "Naturel", -0.04f, 0.30f, 0.00f, 0.88f, 0.05f, 0.025f, 0.45f, 0.35f),
        PhotoStyle("amber", "Ambre", -0.04f, 1.40f, 0.20f, 1.00f, 0.10f, 0.025f, 0.45f, 0.35f),
        PhotoStyle("cool", "Froid", -0.04f, -0.60f, 0.00f, 0.97f, 0.10f, 0.025f, 0.45f, 0.35f),
        PhotoStyle("mono", "N&B", -0.10f, 0.00f, 0.00f, 0.00f, 0.24f, 0.000f, 0.35f, 0.30f, mono = true),
    )

    fun byId(id: String?): PhotoStyle = ALL.firstOrNull { it.id == id } ?: ALL[0]
}

object ColorScience {

    fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    fun linearToSrgb(c: Float): Float {
        val v = c.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) v * 12.92f else (1.055 * v.toDouble().pow(1 / 2.4) - 0.055).toFloat()
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /** Signed smallest difference between two hue angles in degrees. */
    private fun hueDiff(a: Float, b: Float): Float {
        var d = (a - b) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    private fun bump(h: Float, center: Float, width: Float): Float {
        val d = hueDiff(h, center) / width
        return exp(-0.5f * d * d)
    }

    // --- Oklab (Björn Ottosson) on linear sRGB ---

    private fun linToOklab(r: Float, g: Float, b: Float, out: FloatArray) {
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        out[0] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        out[1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        out[2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    }

    private fun oklabToLin(L: Float, a: Float, b: Float, out: FloatArray) {
        val l = L + 0.3963377774f * a + 0.2158037573f * b
        val m = L - 0.1055613458f * a - 0.0638541728f * b
        val s = L - 0.0894841775f * a - 1.2914855480f * b
        val l3 = l * l * l
        val m3 = m * m * m
        val s3 = s * s * s
        out[0] = 4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3
        out[1] = -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3
        out[2] = -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3
    }

    /** Midtone S-curve pivoting on Oklab L of 18% grey; endpoints stay fixed. */
    private fun toneCurve(x: Float, c: Float): Float {
        val p = 0.565f
        val e = 1f + c
        return if (x <= p) p * (x / p).toDouble().pow(e.toDouble()).toFloat()
        else 1f - (1f - p) * ((1f - x) / (1f - p)).toDouble().pow(e.toDouble()).toFloat()
    }

    /**
     * The global part of the iPhone look. Input and output are display-referred sRGB in 0..1.
     * [out] receives the result.
     */
    fun transform(r0: Float, g0: Float, b0: Float, style: PhotoStyle, out: FloatArray) {
        val gain = 2f.pow(style.exposureEv)
        var r = srgbToLinear(r0) * gain
        var g = srgbToLinear(g0) * gain
        var b = srgbToLinear(b0) * gain

        // White balance shift, luminance preserving.
        val y0 = 0.2126f * r + 0.7152f * g + 0.0722f * b
        r *= 1f + 0.035f * style.warmth
        g *= 1f - 0.020f * style.tint
        b *= 1f - 0.050f * style.warmth
        val y1 = 0.2126f * r + 0.7152f * g + 0.0722f * b
        if (y1 > 1e-6f) {
            val k = y0 / y1
            r *= k; g *= k; b *= k
        }

        val lab = FloatArray(3)
        linToOklab(r.coerceAtLeast(0f), g.coerceAtLeast(0f), b.coerceAtLeast(0f), lab)
        var L = lab[0]
        var C = hypot(lab[1], lab[2])
        var h = Math.toDegrees(atan2(lab[2], lab[1]).toDouble()).toFloat()
        if (h < 0) h += 360f

        // Hue-selective corrections (Oklab hue angles).
        val wRed = bump(h, 25f, 16f)
        val wSkin = bump(h, 60f, 18f)
        val wGreen = bump(h, 140f, 24f)
        val wCyan = bump(h, 215f, 18f)
        val wBlue = bump(h, 262f, 20f)

        // Foliage toward olive/yellow, skies from cyan toward azure, skin pulled to a warm centre.
        h += -9f * wGreen + 10f * wCyan + 0.18f * wSkin * hueDiff(62f, h)
        if (h < 0) h += 360f

        var chromaScale = style.chroma
        chromaScale *= (1f + 0.04f * wGreen) * (1f - 0.06f * wCyan) * (1f + 0.03f * wRed) * (1f + 0.03f * wBlue)
        // Keep skin close to its original richness whatever the style does to the rest.
        if (!style.mono) chromaScale += (maxOf(chromaScale, 0.97f) - chromaScale) * wSkin * 0.6f
        C *= chromaScale

        // Neutral highlights and calmer deep shadows.
        C *= 1f - 0.45f * smoothstep(0.80f, 1.0f, L)
        C *= 1f - 0.18f * (1f - smoothstep(0.05f, 0.30f, L))

        L = toneCurve(L.coerceIn(0f, 1f), style.contrast)
        // Soft shoulder: sunlit whites sit just below full white instead of clipping.
        L -= 0.025f * smoothstep(0.75f, 1.0f, L)
        L = style.blackLift + (1f - style.blackLift) * L
        if (style.mono) C = 0f

        val hr = Math.toRadians(h.toDouble())
        val ca = cos(hr).toFloat()
        val sa = sin(hr).toFloat()
        val rgb = FloatArray(3)
        oklabToLin(L, C * ca, C * sa, rgb)
        // Gamut mapping: reduce chroma until the colour fits (keeps hue and lightness).
        if (outOfGamut(rgb)) {
            var lo = 0f
            var hi = C
            repeat(10) {
                val mid = (lo + hi) * 0.5f
                oklabToLin(L, mid * ca, mid * sa, rgb)
                if (outOfGamut(rgb)) hi = mid else lo = mid
            }
            oklabToLin(L, lo * ca, lo * sa, rgb)
        }
        out[0] = linearToSrgb(rgb[0])
        out[1] = linearToSrgb(rgb[1])
        out[2] = linearToSrgb(rgb[2])
    }

    private fun outOfGamut(c: FloatArray): Boolean {
        val eps = 1e-4f
        return c[0] < -eps || c[1] < -eps || c[2] < -eps || c[0] > 1 + eps || c[1] > 1 + eps || c[2] > 1 + eps
    }
}

/**
 * 3D colour lookup table baked from [ColorScience.transform]. The same table drives both the
 * live GPU preview and the full-resolution CPU pipeline, so what you see is what you get.
 */
class Lut3D(val style: PhotoStyle, val size: Int = 33) {
    /** RGB bytes, red fastest, then green, then blue (matches GL_TEXTURE_3D layout). */
    val bytes: ByteArray = ByteArray(size * size * size * 3)
    private val values = FloatArray(size * size * size * 3)

    init {
        val tmp = FloatArray(3)
        val n = (size - 1).toFloat()
        var i = 0
        for (bi in 0 until size) for (gi in 0 until size) for (ri in 0 until size) {
            ColorScience.transform(ri / n, gi / n, bi / n, style, tmp)
            for (c in 0..2) {
                values[i + c] = tmp[c]
                bytes[i + c] = (tmp[c] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
            i += 3
        }
    }

    // Per 8-bit input value: lower grid index and interpolation weight.
    private val idx = IntArray(256)
    private val frac = FloatArray(256)

    init {
        val n = size - 1
        for (v in 0..255) {
            val p = v / 255f * n
            val i0 = minOf(p.toInt(), n - 1)
            idx[v] = i0
            frac[v] = p - i0
        }
    }

    /** Trilinear lookup of an 8-bit sRGB colour, returns packed opaque ARGB. */
    fun apply(r: Int, g: Int, b: Int): Int {
        val s = size
        val r0 = idx[r]; val g0 = idx[g]; val b0 = idx[b]
        val fr = frac[r]; val fg = frac[g]; val fb = frac[b]
        val v = values
        val sr = 3
        val sg = 3 * s
        val sb = 3 * s * s
        val base = r0 * sr + g0 * sg + b0 * sb
        var outPacked = 0xFF shl 24
        for (c in 0..2) {
            val p = base + c
            val c00 = v[p] + (v[p + sr] - v[p]) * fr
            val c10 = v[p + sg] + (v[p + sg + sr] - v[p + sg]) * fr
            val c01 = v[p + sb] + (v[p + sb + sr] - v[p + sb]) * fr
            val c11 = v[p + sb + sg] + (v[p + sb + sg + sr] - v[p + sb + sg]) * fr
            val c0 = c00 + (c10 - c00) * fg
            val c1 = c01 + (c11 - c01) * fg
            val res = ((c0 + (c1 - c0) * fb) * 255f + 0.5f).toInt().coerceIn(0, 255)
            outPacked = outPacked or (res shl (16 - 8 * c))
        }
        return outPacked
    }
}
