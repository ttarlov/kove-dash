package com.kovedash.app.ui.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kovedash.app.ui.theme.KoveColors
import com.kovedash.app.ui.theme.KoveFonts
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * V2 smoke test. Black 1280x640 face with KOVEDASH header, live timestamp updating every
 * ~33ms, and a status line. The point: prove the encoder + VirtualDisplay + Presentation
 * pipeline delivers live Compose pixels to the dash. No Mapbox here — that comes after
 * this passes a bench test.
 */
@Composable
fun SmokeClockFace() {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(33L)
        }
    }
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    Box(
        modifier = Modifier.fillMaxSize().background(KoveColors.Void),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "KOVEDASH · V2 SMOKE",
                color = KoveColors.Mint,
                fontFamily = KoveFonts.PressStart2P,
                fontSize = 22.sp,
                letterSpacing = 0.15.sp,
            )
            Spacer(Modifier.height(36.dp))
            Text(
                text = fmt.format(Date(now)),
                color = KoveColors.Yellow,
                fontFamily = KoveFonts.Bungee,
                fontSize = 88.sp,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "encoder + presentation + virtualdisplay live",
                color = KoveColors.Sky,
                fontFamily = KoveFonts.VT323,
                fontSize = 26.sp,
            )
        }
    }
}
