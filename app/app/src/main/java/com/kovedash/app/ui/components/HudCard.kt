package com.kovedash.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kovedash.app.ui.theme.KoveColors
import com.kovedash.app.ui.theme.KoveFonts

/**
 * Live-stream HUD card. Dark inset, mint border, magenta drop-shadow.
 * Big Bungee numeric readout with dimmed leading zeros, FPS unit tag,
 * frame-count pill, optional split-time chips.
 */
@Composable
fun HudCard(
    headlineNumber: Int,
    headlineUnit: String,
    framePill: String,
    splits: List<HudSplit> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val infinite = rememberInfiniteTransition(label = "live-blink")
    val badgeAlpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 0.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "blink",
    )

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .offset(4.dp, 4.dp)
                .fillMaxWidth()
                .background(KoveColors.Magenta)
                .padding(12.dp),
        ) {
            Text(
                text = " ",
                color = androidx.compose.ui.graphics.Color.Transparent,
                fontFamily = KoveFonts.Bungee,
                fontSize = 56.sp,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(KoveColors.Ink)
                .border(2.dp, KoveColors.Mint)
                .padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = 12.dp),
        ) {
            // ● LIVE blinking badge
            Box(
                modifier = Modifier
                    .offset(y = (-26).dp)
                    .background(KoveColors.Magenta.copy(alpha = badgeAlpha))
                    .border(2.dp, KoveColors.Ink)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "● LIVE",
                    color = KoveColors.Paper,
                    fontFamily = KoveFonts.PressStart2P,
                    fontSize = 7.sp,
                    letterSpacing = 0.1.sp,
                )
            }
            // Hero number
            val formatted = "%03d".format(headlineNumber)
            val lead = formatted.takeWhile { it == '0' }.dropLast(1)
            val tail = formatted.removePrefix(lead)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = buildAnnotatedString {
                        if (lead.isNotEmpty()) {
                            withStyle(SpanStyle(color = KoveColors.Paper.copy(alpha = 0.22f))) {
                                append(lead)
                            }
                        }
                        withStyle(SpanStyle(color = KoveColors.Paper)) { append(tail) }
                    },
                    fontFamily = KoveFonts.Bungee,
                    fontSize = 56.sp,
                    lineHeight = 56.sp,
                    letterSpacing = 0.01.sp,
                )
                Text(
                    text = headlineUnit,
                    color = KoveColors.Mint,
                    fontFamily = KoveFonts.PressStart2P,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            // Frame-count pill
            Box(
                modifier = Modifier
                    .background(KoveColors.Magenta)
                    .border(2.dp, KoveColors.Ink)
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            ) {
                Text(
                    text = framePill,
                    color = KoveColors.Paper,
                    fontFamily = KoveFonts.PressStart2P,
                    fontSize = 7.sp,
                    letterSpacing = 0.06.sp,
                )
            }
            if (splits.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    splits.forEach { s ->
                        SplitChip(s)
                    }
                }
            }
        }
    }
}

data class HudSplit(val text: String, val kind: SplitKind)
enum class SplitKind { Good, Bad, Info }

@Composable
private fun SplitChip(split: HudSplit) {
    val (bg, fg) = when (split.kind) {
        SplitKind.Good -> KoveColors.Mint to KoveColors.Ink
        SplitKind.Bad -> KoveColors.Magenta to KoveColors.Paper
        SplitKind.Info -> KoveColors.Sky to KoveColors.Ink
    }
    Box(
        modifier = Modifier
            .background(bg)
            .border(1.5.dp, KoveColors.Ink)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = split.text,
            color = fg,
            fontFamily = KoveFonts.VT323,
            fontSize = 14.sp,
        )
    }
}
