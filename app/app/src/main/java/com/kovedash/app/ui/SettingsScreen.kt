package com.kovedash.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(
    currentPassword: String?,
    currentSsidPrefix: String,
    notificationsEnabled: Boolean,
    onNotificationsToggle: (Boolean) -> Unit,
    onSave: (password: String, ssidPrefix: String) -> Unit,
    onBack: () -> Unit,
) {
    var password by remember { mutableStateOf(currentPassword ?: "") }
    var ssidPrefix by remember { mutableStateOf(currentSsidPrefix) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Settings",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )

        OutlinedTextField(
            value = ssidPrefix,
            onValueChange = { ssidPrefix = it },
            label = { Text("Dash AP SSID prefix") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Dash AP password") },
            placeholder = { Text("rotates on dash reset") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = "Note: the dash AP password rotates whenever the dash is reset. " +
                "If auto-connect fails repeatedly, update the password here.",
            color = Color(0xFF8FA0B0),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )

        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Forward notifications to dash", color = Color.White, fontSize = 16.sp)
                Text(
                    "Show allow-listed app notifications (texts, Signal, WhatsApp…) " +
                        "as a banner on the dash. Needs Notification Access.",
                    color = Color(0xFF8FA0B0),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(0.dp))
            Switch(checked = notificationsEnabled, onCheckedChange = onNotificationsToggle)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
            ) { Text("Cancel") }
            Button(
                onClick = {
                    onSave(
                        password.trim().ifBlank { "" },
                        ssidPrefix.trim().ifBlank { "CQKY_" },
                    )
                },
                modifier = Modifier.weight(1f),
                enabled = password.isNotBlank() && ssidPrefix.isNotBlank(),
            ) { Text("Save") }
        }
    }
}
