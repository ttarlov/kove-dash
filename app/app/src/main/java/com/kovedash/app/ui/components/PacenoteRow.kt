package com.kovedash.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kovedash.app.ui.theme.KoveColors
import com.kovedash.app.ui.theme.KoveFonts

/**
 * Co-driver pacenote row. Chevron + message + status tag.
 *
 * States:
 *   Pending — dim, "···"
 *   Live    — magenta border, amber chevron, blinking "SEND"
 *   Done    — mint border + soft mint bg, "ACK"
 */
enum class PacenoteState { Pending, Live, Done }

@Composable
fun PacenoteRow(
    body: String,
    state: PacenoteState,
    modifier: Modifier = Modifier,
) {
    val (bg, border) = when (state) {
        PacenoteState.Pending -> KoveColors.Void2 to KoveColors.Hairline2
        PacenoteState.Live -> Color(0x33_DC1283) to KoveColors.Magenta
        PacenoteState.Done -> Color(0x14_02D49E) to Color(0x66_02D49E)
    }
    val chevColor = when (state) {
        PacenoteState.Pending -> KoveColors.Paper.copy(alpha = 0.3f)
        PacenoteState.Live -> KoveColors.Yellow
        PacenoteState.Done -> KoveColors.Mint
    }
    val tagColor = chevColor
    val tagText = when (state) {
        PacenoteState.Pending -> "···"
        PacenoteState.Live -> "SEND"
        PacenoteState.Done -> "ACK"
    }
    val bodyColor = if (state == PacenoteState.Pending)
        KoveColors.Paper.copy(alpha = 0.45f) else KoveColors.Paper

    Row(
        modifier = modifier
            .background(bg)
            .border(1.dp, border)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "▸",
            color = chevColor,
            fontFamily = KoveFonts.BowlbyOne,
            fontStyle = FontStyle.Italic,
            fontSize = 16.sp,
        )
        Text(
            text = body,
            color = bodyColor,
            fontFamily = KoveFonts.VT323,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = tagText,
            color = tagColor,
            fontFamily = KoveFonts.PressStart2P,
            fontSize = 7.sp,
            letterSpacing = 0.06.sp,
        )
    }
}
