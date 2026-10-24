package com.vindovinou.ilook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ColorScienceTest {

    private val standard = Styles.byId("standard")

    private fun t(r: Float, g: Float, b: Float, style: PhotoStyle = standard) =
        FloatArray(3).also { ColorScience.transform(r, g, b, style, it) }

    private fun luma(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    @Test
    fun endpointsStayBlackAndWhite() {
        val black = t(0f, 0f, 0f)
        val white = t(1f, 1f, 1f)
        assertTrue(black.all { it < 0.06f })
        assertTrue(white.all { it > 0.9f })
    }

    @Test
    fun toneIsMonotonic() {
        for (style in Styles.ALL) {
            var prev = -1f
            for (i in 0..64) {
                val v = i / 64f
                val y = luma(t(v, v, v, style))
                assertTrue("${style.id} at $v", y >= prev - 1e-4f)
                prev = y
            }
        }
    }

    @Test
    fun standardIsSlightlyWarmOnGrey() {
        val g = t(0.5f, 0.5f, 0.5f)
        assertTrue(g[0] > g[2])
        assertTrue(g[0] - g[2] < 0.05f)
    }

    @Test
    fun greensLeanTowardYellow() {
        val src = floatArrayOf(0.25f, 0.65f, 0.2f)
        val out = t(src[0], src[1], src[2])
        assertTrue(out[0] / out[1] > src[0] / src[1])
    }

    @Test
    fun sunlitWhitesStayJustBelowClipping() {
        val white = t(1f, 1f, 1f)
        assertTrue(white.all { it < 0.995f })
    }

    @Test
    fun monoHasNoColour() {
        val out = t(0.8f, 0.3f, 0.2f, Styles.byId("mono"))
        assertEquals(out[0], out[1], 0.01f)
        assertEquals(out[1], out[2], 0.01f)
    }

    @Test
    fun lutMatchesDirectTransform() {
        val lut = Lut3D(standard)
        for ((r, g, b) in listOf(Triple(10, 200, 30), Triple(128, 128, 128), Triple(230, 160, 120), Triple(40, 90, 220))) {
            val direct = t(r / 255f, g / 255f, b / 255f)
            val packed = lut.apply(r, g, b)
            val viaLut = floatArrayOf(((packed shr 16) and 0xFF) / 255f, ((packed shr 8) and 0xFF) / 255f, (packed and 0xFF) / 255f)
            for (c in 0..2) assertTrue("($r,$g,$b)[$c]", abs(direct[c] - viaLut[c]) < 0.02f)
        }
    }
}
