package com.sufficit.ai.mobiledevice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Modo A — advanced/manual fallback reached from the login screen's
 * secondary link. Only authentication here: paste a pairing token generated
 * at /ai/mobile-devices and save it. No network call, no device config —
 * that's a separate concern (tailnet address lives on Home, next to
 * "Sincronizar agora", same as Modo B).
 */
@Composable
fun TokenPairingScreen(
    loading: Boolean,
    status: String?,
    onBack: () -> Unit,
    onPair: (token: String) -> Unit
) {
    var token by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }

        Text(stringResource(R.string.token_pairing_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.token_pairing_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(stringResource(R.string.token_pairing_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !loading
        )

        Button(
            onClick = { onPair(token.trim()) },
            enabled = !loading && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.token_pairing_button))
            }
        }

        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun TokenPairingScreenPreview() {
    SufficitTheme { TokenPairingScreen(loading = false, status = null, onBack = {}, onPair = {}) }
}
