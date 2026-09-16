package com.sixoffive.androidmcp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * Encode [text] as a square grid of QR modules (true = dark). Pure — no Android, no scaling, no
 * quiet zone — so it is unit-testable by decoding the grid back.
 */
fun qrModules(text: String): Array<BooleanArray> {
    val qr = Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
    val m = qr.matrix
    return Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y).toInt() == 1 } }
}

/**
 * Draw a QR of [text] with a 4-module quiet zone on a white tile, so it scans even in dark theme.
 * Rendered with Canvas rather than a Bitmap — no allocation, crisp at any size.
 */
@Composable
fun QrCode(text: String, sizeDp: Int = 200, modifier: Modifier = Modifier) {
    val modules = remember(text) { qrModules(text) }
    val n = modules.size
    val quiet = 4
    val total = n + quiet * 2
    Canvas(modifier.size(sizeDp.dp)) {
        val cell = size.minDimension / total
        drawRect(Color.White, size = Size(cell * total, cell * total))
        for (y in 0 until n) for (x in 0 until n) if (modules[y][x]) {
            drawRect(
                Color.Black,
                topLeft = Offset((x + quiet) * cell, (y + quiet) * cell),
                size = Size(cell, cell),
            )
        }
    }
}
