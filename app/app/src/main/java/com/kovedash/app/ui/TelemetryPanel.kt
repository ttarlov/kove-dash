package com.kovedash.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kovedash.app.service.DashState
import com.kovedash.app.service.TelemetryFinding
import com.kovedash.app.ui.theme.KoveColors
import com.kovedash.app.ui.theme.KoveFonts

@Composable
fun TelemetryPanel(state: DashState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(KoveColors.Void2)
            .border(1.dp, KoveColors.Hairline2)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Kv("Gateway", state.dashGatewayIp ?: "—", if (state.dashGatewayIp != null) KoveColors.Mint else KoveColors.Paper.copy(alpha = 0.35f))
        Kv("Firmware", state.firmware ?: "—", KoveColors.Paper)
        Kv("MAC", state.mac ?: "—", KoveColors.Paper)
        Kv("Device", state.deviceType ?: "—", KoveColors.Yellow)
        if (state.telemetry.isNotEmpty()) {
            androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 6.dp))
            Text(
                text = "—— PROBED ——",
                color = KoveColors.Sky,
                fontFamily = KoveFonts.PressStart2P,
                fontSize = 7.sp,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            state.telemetry.forEach { f -> ProbeRow(f) }
        }
    }
}

@Composable
private fun Kv(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = label.uppercase(),
            color = KoveColors.Sky,
            fontFamily = KoveFonts.PressStart2P,
            fontSize = 8.sp,
            letterSpacing = 0.06.sp,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = value,
            color = valueColor,
            fontFamily = KoveFonts.VT323,
            fontSize = 18.sp,
        )
    }
}

@Composable
private fun ProbeRow(f: TelemetryFinding) {
    val color = if (f.value == null) KoveColors.Sky.copy(alpha = 0.55f) else KoveColors.Paper
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = f.label.uppercase(),
            color = KoveColors.Sky,
            fontFamily = KoveFonts.PressStart2P,
            fontSize = 7.sp,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = f.value ?: "no response",
            color = color,
            fontFamily = KoveFonts.VT323,
            fontSize = 15.sp,
            fontStyle = if (f.value == null) FontStyle.Italic else FontStyle.Normal,
            modifier = Modifier.weight(1f),
        )
    }
}
