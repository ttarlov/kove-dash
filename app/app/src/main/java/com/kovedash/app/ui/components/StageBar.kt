package com.kovedash.app.ui.components

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kovedash.app.ui.theme.KoveColors
import com.kovedash.app.ui.theme.KoveFonts

/**
 * 6-segment chunky progress bar. Each segment is one connection stage.
 * Live segment color-cycles magenta -> yellow to draw the eye.
 */
@Composable
fun StageBar(
    title: String,
    currentStage: Int,
    totalStages: Int = 6,
    stageLabels: List<String> = listOf("WIFI", "BLE", "FW", "CHK", "NAV", "RDY"),
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = title.uppercase(),
                color = KoveColors.Paper,
                fontFamily = KoveFonts.SairaStencilOne,
                fontSize = 14.sp,
                letterSpacing = 0.06.sp,
            )
            Text(
                text = "${"%02d".format(currentStage.coerceAtLeast(0))} / ${"%02d".format(totalStages)}",
                color = KoveColors.Magenta,
                fontFamily = KoveFonts.Bungee,
                fontSize = 18.sp,
            )
        }
        // Bar
        Row(
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .height(14.dp)
                .background(KoveColors.Void)
                .border(1.5.dp, KoveColors.Ink),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            for (i in 1..totalStages) {
                val state = when {
                    i < currentStage -> SegmentState.Done
                    i == currentStage -> SegmentState.Live
                    else -> SegmentState.Pending
                }
                Segment(state = state, modifier = Modifier.weight(1f).fillMaxHeight().padding(1.dp))
            }
        }
        // Labels under each segment
        Row(
            modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            stageLabels.take(totalStages).forEachIndexed { i, label ->
                val isLive = (i + 1) == currentStage
                Text(
                    text = label,
                    color = if (isLive) KoveColors.Magenta else KoveColors.Sky,
                    fontFamily = KoveFonts.PressStart2P,
                    fontSize = 7.sp,
                    letterSpacing = 0.06.sp,
                )
            }
        }
    }
}

private enum class SegmentState { Done, Live, Pending }

@Composable
private fun Segment(state: SegmentState, modifier: Modifier = Modifier) {
    val animatedColor = when (state) {
        SegmentState.Done -> KoveColors.Mint
        SegmentState.Pending -> Color.Transparent
        SegmentState.Live -> {
            val infinite = rememberInfiniteTransition(label = "stage-cycle")
            val color by infinite.animateColor(
                initialValue = KoveColors.Magenta,
                targetValue = KoveColors.Yellow,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 500, easing = androidx.compose.animation.core.LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "stage-color",
            )
            color
        }
    }
    Box(modifier = modifier.background(animatedColor))
}

