package com.kovedash.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.kovedash.app.ui.theme.KoveColors
import com.kovedash.app.ui.theme.KoveFonts

/**
 * Arcade-bevel button. Renders with:
 *   - Outer hard-offset shadow (Ink, 4dp)
 *   - Light top/left bevel inset
 *   - Dark bottom/right bevel inset
 *   - On press: button translates by the shadow offset; shadow collapses
 *
 * Variant determines fill color + bevel highlight/shadow tones.
 */
enum class BeveledButtonVariant { Go, Action, Info, Stop, Ghost, Disabled }

@Composable
fun BeveledButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
    leadingGlyph: String = "►",
    trailingGlyph: String = "»",
    variant: BeveledButtonVariant = BeveledButtonVariant.Go,
    enabled: Boolean = true,
) {
    val effectiveVariant = if (enabled) variant else BeveledButtonVariant.Disabled
    val tones = toneFor(effectiveVariant)
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val shadowOffset = if (isPressed) 2 else 4

    Box(modifier = modifier) {
        // Hard offset shadow underneath
        Box(
            modifier = Modifier
                .offset(x = shadowOffset.dp, y = shadowOffset.dp)
                .fillMaxWidth()
                .height(60.dp)
                .background(KoveColors.Ink),
        )

        // Main button face — bevels are 2dp colored borders, then content padding
        Box(
            modifier = Modifier
                .offset(x = if (isPressed) 2.dp else 0.dp, y = if (isPressed) 2.dp else 0.dp)
                .fillMaxWidth()
                .height(60.dp)
                .background(tones.fill)
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        ) {
            // Top + Left highlight (light)
            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(tones.bevelLight))
            Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(tones.bevelLight))
            // Bottom + Right shadow (dark)
            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(tones.bevelDark).align(Alignment.BottomStart))
            Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(tones.bevelDark).align(Alignment.TopEnd))
            // Outer 1px ink border
            Box(modifier = Modifier.fillMaxWidth().fillMaxHeight().border(2.dp, KoveColors.Ink))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = leadingGlyph,
                    color = tones.text,
                    fontFamily = KoveFonts.Bungee,
                    fontSize = 16.sp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label.uppercase(),
                        color = tones.text,
                        fontFamily = KoveFonts.BowlbyOne,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Normal,
                        fontSize = 16.sp,
                        letterSpacing = 0.04.sp,
                    )
                    if (meta != null) {
                        Text(
                            text = meta,
                            color = tones.meta,
                            fontFamily = KoveFonts.VT323,
                            fontSize = 13.sp,
                        )
                    }
                }
                Text(
                    text = trailingGlyph,
                    color = tones.text,
                    fontFamily = KoveFonts.Bungee,
                    fontSize = 16.sp,
                )
            }
        }
    }
}

private data class Tones(
    val fill: Color,
    val bevelLight: Color,
    val bevelDark: Color,
    val text: Color,
    val meta: Color,
)

private fun toneFor(v: BeveledButtonVariant): Tones = when (v) {
    BeveledButtonVariant.Go -> Tones(
        fill = KoveColors.Mint,
        bevelLight = KoveColors.MintBright,
        bevelDark = KoveColors.MintShadow,
        text = KoveColors.Ink,
        meta = KoveColors.Ink.copy(alpha = 0.65f),
    )
    BeveledButtonVariant.Action -> Tones(
        fill = KoveColors.Yellow,
        bevelLight = KoveColors.YellowHot,
        bevelDark = KoveColors.YellowShadow,
        text = KoveColors.Ink,
        meta = KoveColors.Ink.copy(alpha = 0.7f),
    )
    BeveledButtonVariant.Info -> Tones(
        fill = KoveColors.Sky,
        bevelLight = Color(0xFFC5EDFF),
        bevelDark = KoveColors.SkyDeep,
        text = KoveColors.Ink,
        meta = KoveColors.Ink.copy(alpha = 0.7f),
    )
    BeveledButtonVariant.Stop -> Tones(
        fill = KoveColors.Magenta,
        bevelLight = KoveColors.MagentaHot,
        bevelDark = KoveColors.MagentaShadow,
        text = KoveColors.Paper,
        meta = KoveColors.Paper.copy(alpha = 0.7f),
    )
    BeveledButtonVariant.Ghost -> Tones(
        fill = KoveColors.Void2,
        bevelLight = KoveColors.Purple,
        bevelDark = KoveColors.Ink,
        text = KoveColors.Paper,
        meta = KoveColors.Paper.copy(alpha = 0.55f),
    )
    BeveledButtonVariant.Disabled -> Tones(
        fill = KoveColors.PurpleDim,
        bevelLight = KoveColors.Purple,
        bevelDark = KoveColors.Ink,
        text = KoveColors.Paper.copy(alpha = 0.4f),
        meta = KoveColors.Paper.copy(alpha = 0.3f),
    )
}

