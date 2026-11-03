package io.github.tiendat171195.storedphrase.ui.screens

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.tiendat171195.storedphrase.R
import io.github.tiendat171195.storedphrase.core.backup.BackupFormat
import io.github.tiendat171195.storedphrase.core.backup.PassphraseGenerator
import io.github.tiendat171195.storedphrase.ui.AppViewModel
import io.github.tiendat171195.storedphrase.ui.BackupExportState
import io.github.tiendat171195.storedphrase.ui.LocalAuthenticator
import io.github.tiendat171195.storedphrase.ui.ProgressInfo
import io.github.tiendat171195.storedphrase.ui.components.DebugBanner
import io.github.tiendat171195.storedphrase.ui.components.NoticeCard
import io.github.tiendat171195.storedphrase.ui.components.SecureTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupScaffold(title: String, vm: AppViewModel, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }, enabled = !vm.busy) {
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
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun ProgressLine(progress: ProgressInfo?) {
    if (progress == null) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (progress.total > 0) {
            LinearProgressIndicator(
                progress = { progress.done.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(progress.text, progress.done, progress.total), style = MaterialTheme.typography.bodySmall)
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(progress.text), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// region Export

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupExportScreen(vm: AppViewModel) {
    val state = vm.backupExport ?: return
    val auth = LocalAuthenticator.current
    val count = vm.index?.entries?.size ?: 0
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupFormat.MIME_TYPE)) { uri ->
        vm.endExternalFlow()
        if (uri != null) vm.exportBackup(auth, uri)
    }

    BackupScaffold(stringResource(R.string.backup_export_title), vm) {
        val exported = state.exportedCount
        if (exported != null) {
            NoticeCard(
                text = stringResource(R.string.backup_export_done, exported),
                color = MaterialTheme.colorScheme.primaryContainer,
            )
            Text(stringResource(R.string.backup_export_next_steps), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { vm.back() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_done)) }
            return@BackupScaffold
        }

        Text(stringResource(R.string.backup_export_intro, count), style = MaterialTheme.typography.bodyMedium)
        NoticeCard(text = stringResource(R.string.backup_storage_advice), color = MaterialTheme.colorScheme.tertiaryContainer)

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.useGenerated,
                onClick = { state.useGenerated = true },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                label = { Text(stringResource(R.string.backup_passphrase_generated)) },
            )
            SegmentedButton(
                selected = !state.useGenerated,
                onClick = {
                    state.useGenerated = false
                    state.confirmedWrittenDown = false
                },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                label = { Text(stringResource(R.string.backup_passphrase_custom)) },
            )
        }

        if (state.useGenerated) GeneratedPassphrase(state) else CustomPassphrase(state)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.confirmedWrittenDown, onCheckedChange = { state.confirmedWrittenDown = it })
            Text(stringResource(R.string.backup_confirm_written), style = MaterialTheme.typography.bodyMedium)
        }
        Text(stringResource(R.string.backup_auth_note), style = MaterialTheme.typography.bodySmall)
        ProgressLine(vm.progress)
        Button(
            onClick = {
                vm.beginExternalFlow()
                try {
                    launcher.launch(vm.backupFileName())
                } catch (e: ActivityNotFoundException) {
                    vm.endExternalFlow()
                    vm.showMessage(R.string.error_no_file_app)
                }
            },
            enabled = state.canExport() && count > 0 && !vm.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.backup_export_button)) }
    }
}

@Composable
private fun GeneratedPassphrase(state: BackupExportState) {
    // Reading the version makes this recompose when a new passphrase is generated.
    val version = state.generatedVersion
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = if (version >= 0) String(state.generated).replace('-', ' ') else "",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
    Text(
        text = stringResource(R.string.backup_generated_hint, PassphraseGenerator.DEFAULT_WORDS, PassphraseGenerator.entropyBits().toInt()),
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(onClick = { state.regenerate() }) { Text(stringResource(R.string.backup_regenerate)) }
}

@Composable
private fun CustomPassphrase(state: BackupExportState) {
    SecureTextField(
        label = stringResource(R.string.backup_passphrase_label),
        initial = state.custom.chars,
        masked = true,
        multiLine = false,
        maxLength = 256,
        onChanged = { state.custom.set(it) },
    )
    SecureTextField(
        label = stringResource(R.string.backup_passphrase_repeat),
        initial = state.customRepeat.chars,
        masked = true,
        multiLine = false,
        maxLength = 256,
        onChanged = { state.customRepeat.set(it) },
    )
    state.validationError()?.let { error ->
        Text(stringResource(error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

// endregion

// region Import

@Composable
fun BackupImportScreen(vm: AppViewModel) {
    val state = vm.backupImport ?: return
    val auth = LocalAuthenticator.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        vm.endExternalFlow()
        if (uri != null) vm.loadBackupFile(uri)
    }
    fun pick() {
        vm.beginExternalFlow()
        try {
            picker.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            vm.endExternalFlow()
            vm.showMessage(R.string.error_no_file_app)
        }
    }

    BackupScaffold(stringResource(R.string.backup_import_title), vm) {
        val preview = state.preview
        when {
            state.done -> {
                NoticeCard(
                    text = stringResource(R.string.backup_import_done, state.imported),
                    color = MaterialTheme.colorScheme.primaryContainer,
                )
                Button(onClick = { vm.back() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_done)) }
            }

            state.file == null -> {
                Text(stringResource(R.string.backup_import_intro), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { pick() }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.backup_pick_file))
                }
            }

            preview == null -> {
                Text(
                    text = stringResource(R.string.backup_file_selected, state.fileName ?: "?"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                SecureTextField(
                    label = stringResource(R.string.backup_passphrase_label),
                    initial = state.passphrase.chars,
                    masked = true,
                    multiLine = false,
                    maxLength = 256,
                    onChanged = { state.passphrase.set(it) },
                )
                ProgressLine(vm.progress)
                Button(
                    onClick = { vm.decryptBackup() },
                    enabled = state.passphrase.length > 0 && !vm.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.backup_decrypt_button)) }
                TextButton(onClick = { pick() }, enabled = !vm.busy) { Text(stringResource(R.string.backup_pick_other)) }
            }

            else -> {
                Text(stringResource(R.string.backup_preview_title), style = MaterialTheme.typography.titleMedium)
                for (entry in preview) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(entry.meta.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = pluralStringResource(R.plurals.words_count, entry.meta.wordCount, entry.meta.wordCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
                if (state.skipped > 0) {
                    Text(
                        text = stringResource(R.string.backup_skipped, state.skipped),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(stringResource(R.string.backup_import_auth_note), style = MaterialTheme.typography.bodySmall)
                ProgressLine(vm.progress)
                Button(
                    onClick = { vm.importBackup(auth) },
                    enabled = preview.isNotEmpty() && !vm.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.backup_import_button, preview.size)) }
                OutlinedButton(onClick = { vm.back() }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        }
    }
}

// endregion
