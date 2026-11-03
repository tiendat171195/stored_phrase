package io.github.tiendat171195.storedphrase.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tiendat171195.storedphrase.BuildConfig
import io.github.tiendat171195.storedphrase.R
import io.github.tiendat171195.storedphrase.data.SettingsStore
import io.github.tiendat171195.storedphrase.ui.AppViewModel
import io.github.tiendat171195.storedphrase.ui.components.DebugBanner
import io.github.tiendat171195.storedphrase.ui.components.SecurityBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    var confirmWipe by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            DebugBanner()
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.auto_lock_title), style = MaterialTheme.typography.titleMedium)
                for (seconds in SettingsStore.AUTO_LOCK_CHOICES) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.setAutoLock(seconds) },
                    ) {
                        RadioButton(selected = vm.autoLockSeconds == seconds, onClick = { vm.setAutoLock(seconds) })
                        Text(autoLockLabel(seconds))
                    }
                }
                HorizontalDivider()

                Text(stringResource(R.string.key_protection_title), style = MaterialTheme.typography.titleMedium)
                SecurityBadge(vm.keyReport)
                val config = vm.keyReport?.config
                Text(
                    text = when {
                        vm.keyReport?.isStrongBox != true -> stringResource(R.string.key_protection_tee)
                        config?.unlockedDeviceRequired == true -> stringResource(R.string.key_protection_strongbox_udr)
                        else -> stringResource(R.string.key_protection_strongbox)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { vm.openDeviceCheck() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.device_check_title))
                }
                HorizontalDivider()

                Text(stringResource(R.string.backup_section_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.backup_section_desc), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = { vm.openBackupExport() },
                    enabled = !vm.index?.entries.isNullOrEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.backup_export_title)) }
                OutlinedButton(onClick = { vm.openBackupImport() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.backup_import_title))
                }
                HorizontalDivider()

                Text(stringResource(R.string.wipe_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.wipe_desc), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { confirmWipe = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.wipe_title), color = MaterialTheme.colorScheme.error)
                }
                HorizontalDivider()

                Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.no_network_note), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (confirmWipe) {
        WipeDialog(
            onConfirm = {
                confirmWipe = false
                vm.wipeAndStartOver()
            },
            onDismiss = { confirmWipe = false },
        )
    }
}

@Composable
private fun autoLockLabel(seconds: Int): String = when (seconds) {
    30 -> stringResource(R.string.lock_30s)
    60 -> stringResource(R.string.lock_1m)
    120 -> stringResource(R.string.lock_2m)
    else -> stringResource(R.string.lock_5m)
}

@Composable
private fun WipeDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val word = stringResource(R.string.wipe_confirm_word)
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wipe_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.wipe_confirm_text, word))
                OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = typed.trim().equals(word, ignoreCase = true)) {
                Text(stringResource(R.string.wipe_title), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
