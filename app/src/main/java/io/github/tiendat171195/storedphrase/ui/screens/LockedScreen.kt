package io.github.tiendat171195.storedphrase.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.tiendat171195.storedphrase.R
import io.github.tiendat171195.storedphrase.ui.AppViewModel
import io.github.tiendat171195.storedphrase.ui.LocalAuthenticator
import io.github.tiendat171195.storedphrase.ui.components.DebugBanner
import io.github.tiendat171195.storedphrase.ui.components.SecurityBadge

@Composable
fun LockedScreen(vm: AppViewModel) {
    val auth = LocalAuthenticator.current
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            DebugBanner()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(72.dp),
                )
                Text(stringResource(R.string.locked_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = stringResource(R.string.locked_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecurityBadge(vm.keyReport)
                Button(
                    onClick = { vm.unlock(auth) },
                    enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.unlock))
                }
            }
        }
    }
}
