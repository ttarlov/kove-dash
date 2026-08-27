package com.kovedash.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kovedash.app.R
import kotlinx.coroutines.delay

/**
 * Cold-start splash: the Rally 450 badge (same art as the launcher icon) fades and zooms in
 * over 3 seconds on a black field, then hands off to the app. Drawn on top of everything so
 * the real UI can boot and auto-connect underneath while this plays.
 *
 * [onFinished] fires once the animation (plus a short hold) completes so the caller can drop
 * the overlay. Meant to run once per process — see the gate in MainActivity.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    // Single driver 0f→1f; alpha and scale both read off it so fade and zoom stay locked together.
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = FADE_MS, easing = LinearOutSlowInEasing),
        )
        delay(HOLD_MS)
        onFinished()
    }

    val p = progress.value
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.splash_logo),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .padding(24.dp)
                .alpha(p)
                // Grows past 1.0 so the badge is still visibly pushing forward as it settles.
                .scale(SCALE_FROM + (SCALE_TO - SCALE_FROM) * p),
        )
    }
}

private const val FADE_MS = 3000
private const val HOLD_MS = 350L
private const val SCALE_FROM = 0.82f
private const val SCALE_TO = 1.06f
