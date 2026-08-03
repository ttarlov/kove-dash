package com.kovedash.app.ui.components

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kovedash.app.ui.theme.KoveColors
import com.kovedash.app.ui.theme.KoveFonts

/**
 * Operator-action banner. Dark inset card with magenta drop-shadow, color-cycling
 * highlighted word, body text in VT323. Used for "PRESS UP!" + similar.
 */
@Composable
fun CriticalBanner(
    headlinePrefix: String,
    highlightedWord: String,
    headlineSuffix: String = "",
    body: AnnotatedString,
    modifier: Modifier = Modifier,
    badge: String = "OPERATOR ACTION",
) {
    val infinite = rememberInfiniteTransition(label = "highlight-cycle")
    val highlightColor by infinite.animateColor(
        initialValue = KoveColors.MintBright,
        targetValue = KoveColors.YellowHot,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "hl",
    )

    Box(modifier = modifier) {
        // Magenta drop-shadow underneath
        Box(
            modifier = Modifier
                .offset(5.dp, 5.dp)
                .fillMaxWidth()
                .background(KoveColors.Magenta)
                .padding(14.dp),
        ) {
            // sized to match foreground via invisible text spacer
            Text(
                text = "$headlinePrefix $highlightedWord $headlineSuffix",
                color = androidx.compose.ui.graphics.Color.Transparent,
                fontFamily = KoveFonts.BungeeInline,
                fontSize = 28.sp,
            )
        }
        // Main banner
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(KoveColors.Void)
                .border(3.dp, KoveColors.Ink)
                .padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 14.dp),
        ) {
            // Tab badge near the top-left
            Row {
                Box(
                    modifier = Modifier
                        .offset(y = (-30).dp)
                        .background(KoveColors.Magenta)
                        .border(2.dp, KoveColors.Ink)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = badge,
                        color = KoveColors.Paper,
                        fontFamily = KoveFonts.PressStart2P,
                        fontSize = 7.sp,
                        letterSpacing = 0.1.sp,
                    )
                }
            }

            // Headline (prefix + colored highlighted word + suffix)
            val headline = buildAnnotatedString {
                withStyle(SpanStyle(color = KoveColors.Yellow)) { append(headlinePrefix) }
                if (highlightedWord.isNotBlank()) {
                    append(" ")
                    withStyle(SpanStyle(color = highlightColor)) { append(highlightedWord) }
                }
                if (headlineSuffix.isNotBlank()) {
                    append(" ")
                    withStyle(SpanStyle(color = KoveColors.Yellow)) { append(headlineSuffix) }
                }
            }
            Text(
                text = headline,
                fontFamily = KoveFonts.BungeeInline,
                fontSize = 26.sp,
                lineHeight = 28.sp,
                letterSpacing = 0.02.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                color = KoveColors.Sky,
                fontFamily = KoveFonts.VT323,
                fontSize = 18.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

