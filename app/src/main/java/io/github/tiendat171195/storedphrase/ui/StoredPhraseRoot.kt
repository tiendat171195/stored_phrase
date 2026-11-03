package io.github.tiendat171195.storedphrase.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.tiendat171195.storedphrase.R
import io.github.tiendat171195.storedphrase.ui.screens.BackupExportScreen
import io.github.tiendat171195.storedphrase.ui.screens.BackupImportScreen
import io.github.tiendat171195.storedphrase.ui.screens.DeviceCheckScreen
import io.github.tiendat171195.storedphrase.ui.screens.EditorScreen
import io.github.tiendat171195.storedphrase.ui.screens.EntryDetailScreen
import io.github.tiendat171195.storedphrase.ui.screens.KeyInvalidatedScreen
import io.github.tiendat171195.storedphrase.ui.screens.LockedScreen
import io.github.tiendat171195.storedphrase.ui.screens.QuizScreen
import io.github.tiendat171195.storedphrase.ui.screens.SettingsScreen
import io.github.tiendat171195.storedphrase.ui.screens.VaultListScreen

@Composable
fun StoredPhraseRoot(vm: AppViewModel) {
    BackHandler(enabled = vm.backStack.size > 1) { vm.back() }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val screen = vm.screen) {
            Screen.DeviceCheck -> DeviceCheckScreen(vm)
            Screen.Locked -> LockedScreen(vm)
            Screen.VaultList -> VaultListScreen(vm)
            Screen.KeyInvalidated -> KeyInvalidatedScreen(vm)
            Screen.Settings -> SettingsScreen(vm)
            Screen.BackupExport -> BackupExportScreen(vm)
            Screen.BackupImport -> BackupImportScreen(vm)
            is Screen.EntryDetail -> EntryDetailScreen(vm, screen.id)
            is Screen.Editor -> EditorScreen(vm)
            is Screen.Quiz -> QuizScreen(vm)
        }
        if (vm.busy) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .align(Alignment.TopCenter),
            )
        }
    }

    vm.message?.let { message ->
        AlertDialog(
            onDismissRequest = { vm.dismissMessage() },
            confirmButton = {
                TextButton(onClick = { vm.dismissMessage() }) { Text(stringResource(R.string.common_ok)) }
            },
            text = {
                Text(
                    if (message.arg != null) stringResource(message.text, message.arg) else stringResource(message.text),
                )
            },
        )
    }
}
