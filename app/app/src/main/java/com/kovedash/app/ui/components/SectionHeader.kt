package com.kovedash.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kovedash.app.ui.theme.KoveColors
import com.kovedash.app.ui.theme.KoveFonts

/**
 * Numbered section header with a 4-color rule line — magenta / yellow / sky / mint
 * banding (the same livery vocabulary as SponsorBand but as a thin accent).
 */
@Composable
fun SectionHeader(
    number: String,
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // §NN pill — yellow text on ink with yellow border
        Row(
            modifier = Modifier
                .background(KoveColors.Ink)
                .border(1.dp, KoveColors.Yellow)
                .padding(horizontal = 5.dp, vertical = 3.dp),
        ) {
            Text(
                text = number,
                color = KoveColors.Yellow,
                fontFamily = KoveFonts.PressStart2P,
                fontSize = 8.sp,
                letterSpacing = 0.04.sp,
            )
        }
        Text(
            text = title,
            color = KoveColors.Paper,
            fontFamily = KoveFonts.BowlbyOne,
            fontStyle = FontStyle.Italic,
            fontSize = 14.sp,
            letterSpacing = 0.04.sp,
        )
        // 4-color rule
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .border(1.dp, KoveColors.Ink)
                .drawBehind {
                    val w = size.width / 4f
                    val palette = listOf(
                        KoveColors.Magenta, KoveColors.Yellow, KoveColors.Sky, KoveColors.Mint,
                    )
                    for (i in palette.indices) {
                        drawRect(
                            color = palette[i],
                            topLeft = Offset(i * w, 0f),
                            size = androidx.compose.ui.geometry.Size(w, size.height),
                        )
                    }
                },
        )
        if (trailing != null) {
            Text(
                text = trailing,
                color = KoveColors.Paper.copy(alpha = 0.55f),
                fontFamily = KoveFonts.PressStart2P,
                fontSize = 7.sp,
                letterSpacing = 0.06.sp,
            )
        }
    }
}
