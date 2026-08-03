package com.kovedash.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kovedash.app.ui.theme.KoveColors

/**
 * Horizontal color-band header/footer — the Camel/Rothmans/Marlboro livery tell.
 *
 * 5 segments with the locked weights (1.2 / 1.0 / 0.8 / 1.0 / 1.4) and colors
 * (Magenta / Yellow / Ink / Sky / Mint). Thin Ink border outside.
 */
@Composable
fun SponsorBand(
    modifier: Modifier = Modifier,
    heightDp: Int = 6,
    borderWidthDp: Float = 1.5f,
) {
    Row(
        modifier = modifier
            .height(heightDp.dp)
            .border(borderWidthDp.dp, KoveColors.Ink),
    ) {
        Band(weight = 1.2f, color = KoveColors.Magenta)
        Band(weight = 1.0f, color = KoveColors.Yellow)
        Band(weight = 0.8f, color = KoveColors.Ink)
        Band(weight = 1.0f, color = KoveColors.Sky)
        Band(weight = 1.4f, color = KoveColors.Mint)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Band(weight: Float, color: Color) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .background(color),
    )
}
