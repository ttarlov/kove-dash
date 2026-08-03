package com.kovedash.app.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/**
 * CRT scanline overlay applied via drawWithContent so it draws ON TOP of children
 * without sitting in the gesture tree. Touches pass through to whatever's below.
 */
fun Modifier.scanlineOverlay(
    color: Color = Color(0x38_000000),
    spacingPx: Float = 3f,
): Modifier = this.drawWithContent {
    drawContent()
    var y = 0f
    while (y < size.height) {
        drawLine(
            color = color,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
        )
        y += spacingPx
    }
}
