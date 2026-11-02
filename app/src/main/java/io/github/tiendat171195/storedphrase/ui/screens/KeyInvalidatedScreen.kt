package io.github.tiendat171195.storedphrase.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tiendat171195.storedphrase.R
import io.github.tiendat171195.storedphrase.ui.AppViewModel

@Composable
fun KeyInvalidatedScreen(vm: AppViewModel) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(56.dp),
            )
            Text(stringResource(R.string.key_invalidated_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.key_invalidated_text), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = { vm.wipeAndStartOver() },
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.key_invalidated_action))
            }
        }
    }
}
