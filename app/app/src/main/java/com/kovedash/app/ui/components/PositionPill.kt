package com.kovedash.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kovedash.app.ui.theme.KoveColors
import com.kovedash.app.ui.theme.KoveFonts

/**
 * Race-position style pill. Used in the header to surface stage code / state.
 *
 * Examples:
 *   PositionPill(label = "04", denom = "/06")          // mid-handshake
 *   PositionPill(label = "RDY", denom = "/06", color = Mint)
 *   PositionPill(label = "LIVE", color = Magenta)      // streaming
 */
@Composable
fun PositionPill(
    label: String,
    modifier: Modifier = Modifier,
    denom: String? = null,
    color: Color = KoveColors.Magenta,
    textColor: Color = KoveColors.Paper,
) {
    androidx.compose.foundation.layout.Box(modifier = modifier) {
        // Offset ink shadow
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .offset(2.dp, 2.dp)
                .background(KoveColors.Ink)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            // sized to match the foreground via the Row inside — use a Spacer that
            // matches the natural text size. Easier: just give it some min size.
            Text(
                text = label + (denom ?: ""),
                color = Color.Transparent,
                fontFamily = KoveFonts.Bungee,
                fontSize = 18.sp,
            )
        }
        Row(
            modifier = Modifier
                .background(color)
                .border(2.dp, KoveColors.Ink)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = label,
                color = textColor,
                fontFamily = KoveFonts.Bungee,
                fontSize = 18.sp,
            )
            if (denom != null) {
                Text(
                    text = denom,
                    color = textColor.copy(alpha = 0.85f),
                    fontFamily = KoveFonts.PressStart2P,
                    fontSize = 8.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}
