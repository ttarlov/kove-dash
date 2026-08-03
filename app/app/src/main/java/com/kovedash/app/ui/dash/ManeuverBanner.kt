package com.kovedash.app.ui.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kovedash.app.nav.Navigator
import com.kovedash.app.nav.RouteProgress
import com.kovedash.app.nav.RouteStatus
import com.kovedash.app.ui.theme.KoveColors
import com.kovedash.app.ui.theme.KoveFonts
import kotlin.math.roundToInt

/**
 * Turn-by-turn maneuver banner. Renders when [Navigator.progress] has a value (i.e. an
 * active route is set and a GPS fix has landed). Two sizes:
 *   - [compact] = false: phone-side banner, taller, full instruction text.
 *   - [compact] = true:  dash-side overlay, condensed for 1280×640 with reduced padding.
 *
 * Distance formatting matches what riders read off the dash from muscle memory:
 *   < 500 ft  → "N FT" rounded to 10 ft
 *   < 0.5 mi  → "X.X MI"
 *   ≥ 0.5 mi  → "X MI" or "X.X MI" depending on magnitude
 *
 * Arrow glyph is a plain Unicode character — no asset baking, no SVG. Looks correct on
 * both the phone (font renderer) and the dash via the encoded H.264 frame.
 */
@Composable
fun ManeuverBanner(modifier: Modifier = Modifier, compact: Boolean = false) {
    val progress by Navigator.progress.collectAsState()
    val status by Navigator.routeStatus.collectAsState()
    // During a reroute, the previous step in `progress` no longer matches the (about
    // to be replaced) polyline. Surface that explicitly instead of letting the banner
    // lie for the ~1 s the refetch takes.
    if (status == RouteStatus.Rerouting) {
        ReroutingBanner(modifier, compact)
        return
    }
    val p = progress ?: return
    // The dash banner is a turn callout — only surface it within HALF_MILE of the
    // maneuver so it "pops up" as you approach instead of sitting there the whole ride.
    // (The phone-side full banner always shows.)
    if (compact && p.distanceToManeuverMeters > HALF_MILE_M) return
    if (compact) CompactBanner(modifier, p) else FullBanner(modifier, p)
}

/**
 * Big upcoming-turn direction arrow for the dash — replaces the (redundant, the dash's
 * own speedo already reads mph) speed HUD. Shows the maneuver direction at a glance
 * whenever a route is active, with the distance under it. Larger and always-on, versus
 * the top banner which only pops up near the turn.
 */
@Composable
fun TurnArrow(modifier: Modifier = Modifier) {
    val progress by Navigator.progress.collectAsState()
    val status by Navigator.routeStatus.collectAsState()
    if (status == RouteStatus.Rerouting) return
    val p = progress ?: return
    // Same frosted rounded panel as the banner; thin white arrow, shadowed for legibility.
    Column(
        modifier = modifier
            .background(KoveColors.Void.copy(alpha = PANEL_ALPHA), PANEL_SHAPE)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = arrowFor(p.step.type, p.step.modifier),
            color = Color.White,
            fontSize = 64.sp,
            style = TextStyle(shadow = HUD_SHADOW),
        )
        Text(
            text = formatDistance(p.distanceToManeuverMeters),
            color = Color.White,
            fontFamily = KoveFonts.PressStart2P,
            fontSize = 14.sp,
            letterSpacing = 0.1.sp,
            style = TextStyle(shadow = HUD_SHADOW),
        )
    }
}

/**
 * Persistent trip readout for the dash — arrival time over distance remaining. Its own
 * element (not inside the banner) so it stays visible even when the turn banner is
 * hidden between maneuvers.
 */
@Composable
fun DashTripHud(modifier: Modifier = Modifier) {
    val progress by Navigator.progress.collectAsState()
    val p = progress ?: return
    if (p.etaSeconds <= 0.0) return
    // Same frosted rounded panel as the banner/arrow.
    Column(
        modifier = modifier
            .background(KoveColors.Void.copy(alpha = PANEL_ALPHA), PANEL_SHAPE)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = formatEta(p.etaSeconds),
            color = Color.White,
            fontFamily = KoveFonts.PressStart2P,
            fontSize = 16.sp,
            letterSpacing = 0.1.sp,
            style = TextStyle(shadow = HUD_SHADOW),
        )
        Text(
            text = formatTripDistance(p.distanceRemainingMeters),
            color = Color.White,
            fontFamily = KoveFonts.VT323,
            fontSize = 22.sp,
            style = TextStyle(shadow = HUD_SHADOW),
        )
    }
}

/**
 * Course-following readout for a loaded GPX (adventure mode): distance remaining, ETA,
 * and percent complete. Frosted panel matching the nav HUD. Shows only when a GPX course
 * is loaded and a GPS fix has landed.
 */
@Composable
fun CourseHud(modifier: Modifier = Modifier) {
    val progress by Navigator.gpxProgress.collectAsState()
    val p = progress ?: return
    Column(
        modifier = modifier
            .background(KoveColors.Void.copy(alpha = PANEL_ALPHA), PANEL_SHAPE)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = "COURSE",
            color = Color(0xFFFF6D00),
            fontFamily = KoveFonts.PressStart2P,
            fontSize = 10.sp,
            letterSpacing = 0.2.sp,
            style = TextStyle(shadow = HUD_SHADOW),
        )
        Text(
            text = "${formatTripDistance(p.remainingMeters)} LEFT",
            color = Color.White,
            fontFamily = KoveFonts.VT323,
            fontSize = 22.sp,
            style = TextStyle(shadow = HUD_SHADOW),
        )
        Text(
            text = buildString {
                if (p.etaSeconds > 0.0) append(formatEta(p.etaSeconds)).append(" · ")
                append("${(p.fractionComplete * 100).roundToInt()}%")
            },
            color = KoveColors.Mint,
            fontFamily = KoveFonts.PressStart2P,
            fontSize = 11.sp,
            style = TextStyle(shadow = HUD_SHADOW),
        )
    }
}

/**
 * Off-course warning for GPX following. Renders only when the rider has strayed off the
 * loaded track (past the deviation threshold for a few fixes).
 */
@Composable
fun OffCourseBanner(modifier: Modifier = Modifier) {
    val progress by Navigator.gpxProgress.collectAsState()
    if (progress?.offCourse != true) return
    val dev = progress?.deviationMeters ?: 0.0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(KoveColors.Void.copy(alpha = 0.5f), PANEL_SHAPE)
            .border(2.dp, KoveColors.Magenta, PANEL_SHAPE)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = "⚠", color = KoveColors.Yellow, fontSize = 30.sp, style = TextStyle(shadow = HUD_SHADOW))
        Box(modifier = Modifier.width(12.dp))
        Text(
            text = "OFF COURSE",
            color = KoveColors.Yellow,
            fontFamily = KoveFonts.PressStart2P,
            fontSize = 18.sp,
            letterSpacing = 0.1.sp,
            style = TextStyle(shadow = HUD_SHADOW),
        )
        Box(modifier = Modifier.weight(1f))
        Text(
            text = "${formatDistance(dev)} OFF",
            color = Color.White,
            fontFamily = KoveFonts.VT323,
            fontSize = 22.sp,
            style = TextStyle(shadow = HUD_SHADOW),
        )
    }
}

// Soft drop shadow that keeps white HUD text readable over any map color.
private val HUD_SHADOW = Shadow(color = Color(0xCC000000), offset = Offset(0f, 2f), blurRadius = 8f)

// Shared frosted-panel look for all dash HUD elements (banner, turn arrow, trip readout).
private const val PANEL_ALPHA = 0.4f
private val PANEL_SHAPE = RoundedCornerShape(14.dp)

@Composable
private fun ReroutingBanner(modifier: Modifier, compact: Boolean) {
    val vPad = if (compact) 6.dp else 10.dp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(if (compact) KoveColors.Void.copy(alpha = 0.88f) else KoveColors.Void2)
            .border(1.dp, KoveColors.Yellow)
            .padding(horizontal = 12.dp, vertical = vPad),
    ) {
        Text(
            text = "↻",
            color = KoveColors.Yellow,
            fontSize = (if (compact) 22 else 28).sp,
        )
        Box(modifier = Modifier.width(10.dp))
        Text(
            text = "REROUTING…",
            color = KoveColors.Yellow,
            fontFamily = KoveFonts.PressStart2P,
            fontSize = (if (compact) 12 else 14).sp,
            letterSpacing = 0.1.sp,
        )
    }
}

@Composable
private fun FullBanner(modifier: Modifier, p: RouteProgress) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(KoveColors.Void2)
            .border(1.dp, KoveColors.Mint)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        ArrowGlyph(modifier = Modifier.size(44.dp), type = p.step.type, modifier_ = p.step.modifier, fontSize = 32)
        Box(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = formatDistance(p.distanceToManeuverMeters),
                color = KoveColors.Yellow,
                fontFamily = KoveFonts.PressStart2P,
                fontSize = 14.sp,
                letterSpacing = 0.1.sp,
            )
            Text(
                text = p.step.instruction.ifBlank { fallbackInstruction(p.step.type, p.step.modifier) },
                color = KoveColors.Paper,
                fontFamily = KoveFonts.VT323,
                fontSize = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (p.etaSeconds > 0.0) {
            Box(modifier = Modifier.width(12.dp))
            TripReadout(p, big = true)
        }
    }
}

@Composable
private fun CompactBanner(modifier: Modifier, p: RouteProgress) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            // Frosted translucent rounded panel — the map shows softly through it (glassy)
            // rather than a solid black box. Text carries a shadow so it stays legible.
            .background(KoveColors.Void.copy(alpha = PANEL_ALPHA), PANEL_SHAPE)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = arrowFor(p.step.type, p.step.modifier),
            color = Color.White,
            fontSize = 40.sp,
            style = TextStyle(shadow = HUD_SHADOW),
        )
        Box(modifier = Modifier.width(16.dp))
        Text(
            text = formatDistance(p.distanceToManeuverMeters),
            color = Color.White,
            fontFamily = KoveFonts.PressStart2P,
            fontSize = 22.sp,
            letterSpacing = 0.1.sp,
            style = TextStyle(shadow = HUD_SHADOW),
        )
        Box(modifier = Modifier.width(16.dp))
        Text(
            modifier = Modifier.weight(1f),
            text = p.step.instruction.ifBlank { fallbackInstruction(p.step.type, p.step.modifier) },
            color = Color.White,
            fontFamily = KoveFonts.VT323,
            fontSize = 30.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(shadow = HUD_SHADOW),
        )
    }
}

/**
 * Trailing trip-to-destination readout: estimated arrival time over distance
 * remaining. Right-aligned so the eye lands on it consistently.
 */
@Composable
private fun TripReadout(p: RouteProgress, big: Boolean) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = formatEta(p.etaSeconds),
            color = KoveColors.Mint,
            fontFamily = KoveFonts.PressStart2P,
            fontSize = (if (big) 14 else 12).sp,
            letterSpacing = 0.1.sp,
        )
        Text(
            text = formatTripDistance(p.distanceRemainingMeters),
            color = KoveColors.Paper,
            fontFamily = KoveFonts.VT323,
            fontSize = (if (big) 18 else 16).sp,
        )
    }
}

@Composable
private fun ArrowGlyph(modifier: Modifier, type: String, modifier_: String?, fontSize: Int) {
    val glyph = arrowFor(type, modifier_)
    Box(
        modifier = modifier
            .background(KoveColors.PurpleDeep)
            .border(1.dp, KoveColors.Mint.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = KoveColors.Mint,
            fontSize = fontSize.sp,
        )
    }
}

private fun arrowFor(type: String, modifier: String?): String = when (type) {
    "arrive" -> "◉"
    "depart" -> "•"
    "roundabout", "rotary", "exit roundabout", "exit rotary", "roundabout turn" -> "↻"
    else -> when (modifier) {
        "left" -> "←"
        "right" -> "→"
        "straight" -> "↑"
        "slight left" -> "↖"
        "slight right" -> "↗"
        "sharp left" -> "⤺"
        "sharp right" -> "⤼"
        "uturn" -> "↶"
        else -> "↑"
    }
}

private fun fallbackInstruction(type: String, modifier: String?): String = when (type) {
    "arrive" -> "Arrive at destination"
    "depart" -> "Begin route"
    else -> buildString {
        append("Turn")
        if (modifier != null) append(' ').append(modifier)
    }
}

internal fun formatDistance(meters: Double): String {
    val feet = meters * 3.28084
    if (feet < 500) {
        val rounded = (feet / 10.0).roundToInt() * 10
        return "$rounded FT"
    }
    val miles = meters / 1609.344
    return when {
        miles < 1.0 -> "%.1f MI".format(miles)
        miles < 10.0 -> "%.1f MI".format(miles)
        else -> "${miles.roundToInt()} MI"
    }
}

// The dash turn banner appears within this distance of the maneuver (~half a mile).
private const val HALF_MILE_M = 804.672

/** Estimated time to arrival: "N MIN" under an hour, else "H:MM". */
internal fun formatEta(seconds: Double): String {
    val totalMin = (seconds / 60.0).roundToInt()
    if (totalMin < 60) return "$totalMin MIN"
    val h = totalMin / 60
    val m = totalMin % 60
    return "%d:%02d".format(h, m)
}

/** Distance remaining to destination, always in miles. */
internal fun formatTripDistance(meters: Double): String {
    val miles = meters / 1609.344
    return if (miles < 10.0) "%.1f MI".format(miles) else "${miles.roundToInt()} MI"
}
