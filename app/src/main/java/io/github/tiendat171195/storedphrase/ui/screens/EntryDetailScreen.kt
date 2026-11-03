package io.github.tiendat171195.storedphrase.ui.screens

import android.text.format.DateUtils
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.tiendat171195.storedphrase.R
import io.github.tiendat171195.storedphrase.core.bip39.Bip39Wordlist
import io.github.tiendat171195.storedphrase.core.vault.IndexEntry
import io.github.tiendat171195.storedphrase.ui.AppViewModel
import io.github.tiendat171195.storedphrase.ui.LocalAuthenticator
import io.github.tiendat171195.storedphrase.ui.RevealSession
import io.github.tiendat171195.storedphrase.ui.components.DebugBanner
import io.github.tiendat171195.storedphrase.ui.components.HoldToShow
import io.github.tiendat171195.storedphrase.ui.components.NoticeCard
import io.github.tiendat171195.storedphrase.ui.components.WordGrid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailScreen(vm: AppViewModel, id: String) {
    val auth = LocalAuthenticator.current
    val entry = vm.entry(id) ?: return
    val reveal = vm.reveal?.takeIf { it.entryId == id }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry.label, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }, enabled = !vm.busy) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete))
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
            ) {
                EntryInfo(entry)
                if (reveal == null) {
                    Text(stringResource(R.string.detail_hidden_hint), style = MaterialTheme.typography.bodyMedium)
                    WordGrid(count = entry.wordCount, wordAt = { null }, masked = { true })
                    Button(
                        onClick = { vm.reveal(auth, id) },
                        enabled = !vm.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.reveal_button)) }
                } else {
                    RevealedContent(vm, reveal)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { vm.startQuiz(auth, id) },
                        enabled = !vm.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.verify_paper)) }
                    OutlinedButton(
                        onClick = { vm.editEntry(auth, id) },
                        enabled = !vm.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.common_edit)) }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteEntry(auth, id)
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun EntryInfo(entry: IndexEntry) {
    val context = LocalContext.current
    val format = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = pluralStringResource(R.plurals.words_count, entry.wordCount, entry.wordCount) +
                (if (entry.hasPassphrase) " · " + stringResource(R.string.has_passphrase_short) else "") +
                (if (entry.hasBirthday) " · " + stringResource(R.string.has_birthday_short) else "") +
                if (entry.layer2) " · " + stringResource(R.string.layer2_short) else "",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.updated_at, DateUtils.formatDateTime(context, entry.updatedAt, format)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = entry.verifiedAt?.let {
                stringResource(R.string.verified_at, DateUtils.getRelativeTimeSpanString(it).toString())
            } ?: stringResource(R.string.verified_never),
            style = MaterialTheme.typography.bodySmall,
            color = if (entry.verifiedAt == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RevealedContent(vm: AppViewModel, reveal: RevealSession) {
    val secret = reveal.secret
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.auto_hide_in, reveal.secondsLeft),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { vm.hideReveal() }) { Text(stringResource(R.string.hide_now)) }
    }
    Text(stringResource(R.string.tap_word_hint), style = MaterialTheme.typography.bodySmall)
    WordGrid(
        count = secret.wordCount,
        wordAt = { position -> if (secret.isWiped) null else Bip39Wordlist.word(secret.wordIndices[position]) },
        masked = { position -> !reveal.isVisible(position) },
        onCellClick = { position ->
            reveal.toggle(position)
            vm.touchReveal()
        },
    )
    HoldToShow(
        label = stringResource(R.string.hold_to_show_all),
        onPressedChange = { pressed ->
            reveal.showAll = pressed
            vm.touchReveal()
        },
        modifier = Modifier.fillMaxWidth(),
    )
    secret.birthdayHeight?.let { height ->
        Text(
            text = stringResource(R.string.birthday_value, height.toString()),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
        )
    }
    if (secret.hasPassphrase) {
        SecretLine(
            title = stringResource(R.string.bip39_passphrase_label),
            visible = reveal.showPassphrase,
            text = { String(secret.bip39Passphrase) },
            onPressedChange = {
                reveal.showPassphrase = it
                vm.touchReveal()
            },
        )
    }
    if (secret.hasNotes) {
        SecretLine(
            title = stringResource(R.string.notes_label),
            visible = reveal.showNotes,
            text = { String(secret.notes) },
            onPressedChange = {
                reveal.showNotes = it
                vm.touchReveal()
            },
        )
    }
    NoticeCard(text = stringResource(R.string.paper_reminder))
}

@Composable
private fun SecretLine(title: String, visible: Boolean, text: () -> String, onPressedChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(
            // Only materialised as a String while the user holds the button.
            text = if (visible) text() else "••••••••",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyLarge,
        )
        HoldToShow(label = stringResource(R.string.hold_to_view), onPressedChange = onPressedChange)
    }
}
