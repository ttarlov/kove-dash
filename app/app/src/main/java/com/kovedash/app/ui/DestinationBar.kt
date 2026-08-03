package com.kovedash.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mapbox.geojson.Point
import com.kovedash.app.AppHost
import com.kovedash.app.nav.Destination
import com.kovedash.app.nav.Navigator
import com.kovedash.app.nav.RouteStatus
import com.kovedash.app.net.MapboxGeocoder
import com.kovedash.app.ui.theme.KoveColors
import com.kovedash.app.ui.theme.KoveFonts
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Compact destination strip. Two visual states:
 *   - No destination set: a tap-target reading "Where to?" — tapping fires
 *     [onActivateSearch] so the parent can present [FullscreenSearch]. We DON'T host an
 *     inline BasicTextField here because in landscape the IME eats >50% of the screen,
 *     collapsing the map (`weight=1f`) and burying the suggestions dropdown.
 *   - Destination set: a chip showing the destination + distance/ETA + clear button.
 *
 * The fullscreen search lives at [FullscreenSearch] and is shown by the Map tab instead
 * of the map when active.
 */
@Composable
fun DestinationBar(
    modifier: Modifier = Modifier,
    onActivateSearch: () -> Unit,
) {
    val destination by Navigator.destination.collectAsState()
    val activeRoute by Navigator.activeRoute.collectAsState()
    val status by Navigator.routeStatus.collectAsState()

    if (destination != null) {
        ActiveDestinationChip(
            modifier = modifier,
            name = destination!!.name,
            context = destination!!.context,
            distanceMeters = activeRoute?.distanceMeters,
            durationSeconds = activeRoute?.durationSeconds,
            status = status,
            onClear = { Navigator.clearDestination() },
        )
    } else {
        TapToSearchBar(modifier = modifier, onClick = onActivateSearch)
    }
}

@Composable
private fun TapToSearchBar(modifier: Modifier, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(KoveColors.Void2)
            .border(1.dp, KoveColors.Yellow)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Text(
            text = "GO",
            color = KoveColors.Yellow,
            fontFamily = KoveFonts.PressStart2P,
            fontSize = 9.sp,
            letterSpacing = 0.1.sp,
        )
        Box(modifier = Modifier.width(8.dp))
        Text(
            text = "Where to?",
            color = KoveColors.Paper.copy(alpha = 0.55f),
            fontFamily = KoveFonts.VT323,
            fontSize = 18.sp,
        )
    }
}

/**
 * Full-screen destination search. Takes over the entire Map tab when active so the
 * keyboard and suggestion list always have enough room, regardless of orientation.
 * Auto-focuses the text field on first composition so the IME opens immediately —
 * no double-tap to start typing.
 *
 * Suggestions render in a LazyColumn so a long list scrolls cleanly above the keyboard.
 * [Modifier.imePadding] on the root keeps the suggestion list above the IME without
 * fighting `adjustResize` (the Window resizes; the LazyColumn fills what's left).
 */
@Composable
fun FullscreenSearch(
    modifier: Modifier = Modifier,
    onDone: () -> Unit,
) {
    val gpsFix by AppHost.gps.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<MapboxGeocoder.Suggestion>>(emptyList()) }
    var retrieving by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    // SearchBox sessions: one UUID covers all /suggest calls + one /retrieve in a
    // 2-minute window for billing. Regenerated each time the overlay opens. After a
    // selection successfully retrieves we close, so the next open gets a fresh token.
    val sessionToken = rememberSaveable { UUID.randomUUID().toString() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LaunchedEffect(query, gpsFix?.lat?.roundToCellKey(), gpsFix?.lon?.roundToCellKey()) {
        if (query.length < 2) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        suggestions = MapboxGeocoder.suggest(query, sessionToken, gpsFix)
    }

    fun select(sug: MapboxGeocoder.Suggestion) {
        if (retrieving) return
        retrieving = true
        focusManager.clearFocus()
        scope.launch {
            val r = MapboxGeocoder.retrieve(sug.mapboxId, sessionToken)
            retrieving = false
            if (r != null) {
                Navigator.setDestination(
                    Destination(
                        name = r.name,
                        context = r.context,
                        point = Point.fromLngLat(r.lon, r.lat),
                    )
                )
                onDone()
            }
            // On retrieve failure we stay on the search overlay so the user can
            // try another suggestion. The HTTP error already logged via Log.w.
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(KoveColors.Void)
            .imePadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(KoveColors.Void2)
                .border(1.dp, KoveColors.Yellow)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            Text(
                text = "GO",
                color = KoveColors.Yellow,
                fontFamily = KoveFonts.PressStart2P,
                fontSize = 9.sp,
                letterSpacing = 0.1.sp,
            )
            Box(modifier = Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                textStyle = TextStyle(color = KoveColors.Paper, fontSize = 20.sp, fontFamily = KoveFonts.VT323),
                cursorBrush = SolidColor(KoveColors.Yellow),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { suggestions.firstOrNull()?.let(::select) },
                ),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            text = "Where to?",
                            color = KoveColors.Paper.copy(alpha = 0.35f),
                            fontFamily = KoveFonts.VT323,
                            fontSize = 20.sp,
                        )
                    }
                    inner()
                },
            )
            Box(modifier = Modifier.width(10.dp))
            CancelButton(onClick = {
                focusManager.clearFocus()
                onDone()
            })
        }

        if (retrieving) {
            Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    text = "FETCHING DESTINATION…",
                    color = KoveColors.Yellow,
                    fontFamily = KoveFonts.PressStart2P,
                    fontSize = 11.sp,
                    letterSpacing = 0.1.sp,
                )
            }
        } else if (suggestions.isEmpty() && query.length >= 2) {
            Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    text = "No matches.",
                    color = KoveColors.Sky.copy(alpha = 0.55f),
                    fontFamily = KoveFonts.VT323,
                    fontSize = 18.sp,
                )
            }
        } else if (suggestions.isNotEmpty()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(suggestions) { sug ->
                    SuggestionRow(
                        name = sug.name,
                        context = sug.context,
                        featureType = sug.featureType,
                        onClick = { select(sug) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(name: String, context: String, featureType: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(
            text = featureTypeGlyph(featureType),
            color = featureTypeColor(featureType),
            fontFamily = KoveFonts.PressStart2P,
            fontSize = 13.sp,
            modifier = Modifier.width(28.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = name,
                color = KoveColors.Mint,
                fontFamily = KoveFonts.VT323,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (context.isNotBlank()) {
                Text(
                    text = context,
                    color = KoveColors.Sky.copy(alpha = 0.7f),
                    fontFamily = KoveFonts.VT323,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun featureTypeGlyph(featureType: String): String = when (featureType) {
    "poi" -> "•"
    "address" -> "#"
    "place", "locality" -> "◯"
    "neighborhood" -> "◌"
    "street" -> "/"
    else -> "·"
}

private fun featureTypeColor(featureType: String): Color = when (featureType) {
    "poi" -> KoveColors.Yellow
    "address" -> KoveColors.Mint
    "place", "locality" -> KoveColors.Sky
    else -> KoveColors.Paper.copy(alpha = 0.55f)
}

@Composable
private fun ActiveDestinationChip(
    modifier: Modifier,
    name: String,
    context: String,
    distanceMeters: Double?,
    durationSeconds: Double?,
    status: RouteStatus,
    onClear: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(KoveColors.Void2)
            .border(1.dp, KoveColors.Mint)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = "→",
            color = KoveColors.Mint,
            fontFamily = KoveFonts.PressStart2P,
            fontSize = 10.sp,
        )
        Box(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = name,
                color = KoveColors.Paper,
                fontFamily = KoveFonts.VT323,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitleFor(status, distanceMeters, durationSeconds, context),
                color = subtitleColor(status),
                fontFamily = KoveFonts.VT323,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ClearButton(onClick = onClear)
    }
}

@Composable
private fun ClearButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(KoveColors.MagentaShadow)
            .border(1.dp, KoveColors.Magenta)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "X",
            color = KoveColors.Paper,
            fontFamily = KoveFonts.PressStart2P,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun CancelButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(KoveColors.PurpleDeep)
            .border(1.dp, KoveColors.Sky.copy(alpha = 0.7f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "X",
            color = KoveColors.Sky,
            fontFamily = KoveFonts.PressStart2P,
            fontSize = 11.sp,
        )
    }
}

private fun subtitleFor(
    status: RouteStatus,
    distanceMeters: Double?,
    durationSeconds: Double?,
    context: String,
): String = when (status) {
    RouteStatus.Idle -> context
    RouteStatus.WaitingForGps -> "WAITING ON GPS…"
    RouteStatus.Fetching -> "FETCHING ROUTE…"
    RouteStatus.Rerouting -> "REROUTING…"
    RouteStatus.Active -> formatEta(distanceMeters, durationSeconds) ?: context
    RouteStatus.Error -> "ROUTE FAILED · TAP X TO RETRY"
}

private fun subtitleColor(status: RouteStatus): Color = when (status) {
    RouteStatus.Active -> KoveColors.Mint
    RouteStatus.Error -> KoveColors.Magenta
    RouteStatus.WaitingForGps, RouteStatus.Fetching, RouteStatus.Rerouting -> KoveColors.Yellow
    RouteStatus.Idle -> KoveColors.Sky.copy(alpha = 0.7f)
}

private fun formatEta(distanceMeters: Double?, durationSeconds: Double?): String? {
    if (distanceMeters == null || durationSeconds == null) return null
    val miles = distanceMeters / 1609.344
    val minutes = (durationSeconds / 60.0).roundToInt()
    val distStr = if (miles >= 10) "${miles.roundToInt()} MI" else "%.1f MI".format(miles)
    return "$distStr · $minutes MIN"
}

// Quantize lat/lon to a ~10m grid so trivial GPS jitter doesn't re-fire the geocoder.
private fun Double.roundToCellKey(): Long = (this * 10_000.0).toLong()
