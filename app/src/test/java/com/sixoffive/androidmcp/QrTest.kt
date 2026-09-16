package com.sixoffive.androidmcp

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.sixoffive.androidmcp.ui.qrModules
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The QR the app shows must actually scan back to the exact connect command — a picture that looks
 * like a QR but decodes to something else is worse than no QR. This encodes with the app's own
 * `qrModules`, renders it to a luminance grid, and decodes it with zxing's reader.
 */
class QrTest {

    /** A luminance view of a module grid: each module is [scale]px, with a [quiet]-module margin. */
    private class ModuleSource(
        private val modules: Array<BooleanArray>,
        private val scale: Int,
        private val quiet: Int,
    ) : LuminanceSource((modules.size + quiet * 2) * scale, (modules.size + quiet * 2) * scale) {
        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val r = row ?: ByteArray(width)
            for (x in 0 until width) r[x] = luminance(x, y)
            return r
        }
        override fun getMatrix(): ByteArray {
            val out = ByteArray(width * height)
            for (y in 0 until height) for (x in 0 until width) out[y * width + x] = luminance(x, y)
            return out
        }
        private fun luminance(px: Int, py: Int): Byte {
            val n = modules.size
            val mx = px / scale - quiet
            val my = py / scale - quiet
            val dark = my in 0 until n && mx in 0 until n && modules[my][mx]
            return if (dark) 0 else 255.toByte()
        }
    }

    private fun decode(text: String): String {
        val src = ModuleSource(qrModules(text), scale = 8, quiet = 4)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(src))).text
    }

    @Test
    fun `a full connect command round-trips through encode and decode`() {
        val cmd = "claude mcp add --transport http phone http://127.0.0.1:8765/mcp " +
            "--header \"Authorization: Bearer IzkdyYWpmkWaY_SeWRLp7lhd7wvfYGaz\""
        assertEquals(cmd, decode(cmd))
    }

    @Test
    fun `modules are a square with the three finder-pattern corners dark`() {
        val m = qrModules("hello")
        val n = m.size
        assertTrue(n >= 21, "the smallest QR is 21x21")
        assertEquals(n, m[0].size, "the grid must be square")
        assertTrue(m[0][0] && m[0][n - 1] && m[n - 1][0], "finder patterns sit at three corners")
    }
}
