package com.vindovinou.ilook

import org.junit.Test
import java.io.File

/** Writes before/after colour charts (PPM) to build/charts for eyeballing the look (not an assertion). */
class PreviewChart {
    @Test
    fun writeChart() {
        val patches = listOf(
            0xFFFFFF, 0xC8C8C8, 0x969696, 0x646464, 0x323232, 0x0A0A0A,
            0xE8B898, 0xC68863, 0x8D5524, 0xF2D2BD, 0xD9A066, 0x5C3A21, // skin
            0x3FA34D, 0x7CC242, 0x1F6E2E, 0x9ACD32, 0x2E8B57, 0x556B2F, // foliage
            0x4FC3F7, 0x29B6F6, 0x0288D1, 0x87CEEB, 0x1E3A8A, 0x6EC6FF, // sky / blue
            0xE53935, 0xFF7043, 0xFFCA28, 0xAB47BC, 0xEC407A, 0x26C6DA,
        )
        val cell = 80
        val cols = 6
        val rows = patches.size / cols
        for (style in Styles.ALL) {
            val w = cols * cell * 2 + 20
            val h = rows * cell
            val img = IntArray(w * h)
            val lut = Lut3D(style)
            for ((i, p) in patches.withIndex()) {
                val x0 = (i % cols) * cell
                val y0 = (i / cols) * cell
                val out = lut.apply((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF) and 0xFFFFFF
                for (y in 0 until cell) for (x in 0 until cell) {
                    img[(y0 + y) * w + x0 + x] = p
                    img[(y0 + y) * w + cols * cell + 20 + x0 + x] = out
                }
            }
            File("build/charts").mkdirs()
            val bytes = ByteArray(w * h * 3)
            for (i in img.indices) {
                bytes[i * 3] = (img[i] shr 16).toByte()
                bytes[i * 3 + 1] = (img[i] shr 8).toByte()
                bytes[i * 3 + 2] = img[i].toByte()
            }
            File("build/charts/${style.id}.ppm").writeBytes("P6\n$w $h\n255\n".toByteArray() + bytes)
        }
    }
}
