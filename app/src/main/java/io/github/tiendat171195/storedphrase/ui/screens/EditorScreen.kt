package io.github.tiendat171195.storedphrase.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.tiendat171195.storedphrase.R
import io.github.tiendat171195.storedphrase.core.bip39.Bip39Wordlist
import io.github.tiendat171195.storedphrase.core.bip39.Mnemonic
import io.github.tiendat171195.storedphrase.core.bip39.SeedInput
import io.github.tiendat171195.storedphrase.core.vault.EntryCodec
import io.github.tiendat171195.storedphrase.ui.AppViewModel
import io.github.tiendat171195.storedphrase.ui.LocalAuthenticator
import io.github.tiendat171195.storedphrase.ui.SeedDraft
import io.github.tiendat171195.storedphrase.ui.components.Bip39Keyboard
import io.github.tiendat171195.storedphrase.ui.components.DebugBanner
import io.github.tiendat171195.storedphrase.ui.components.SecureTextField
import io.github.tiendat171195.storedphrase.ui.components.WordGrid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: AppViewModel) {
    val draft = vm.draft ?: return
    val auth = LocalAuthenticator.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var wordsActive by remember { mutableStateOf(true) }
    var confirmDiscard by remember { mutableStateOf(false) }

    fun leave() {
        if (draft.dirty) confirmDiscard = true else vm.discardDraft()
    }

    fun activateWords() {
        focusManager.clearFocus()
        keyboardController?.hide()
        wordsActive = true
    }

    BackHandler { leave() }

    // Reading version subscribes this composable to every change of the IntArray-backed input.
    val revision = draft.version
    val input = draft.input
    val complete = input.isComplete()
    val checksumOk = complete && input.isChecksumValid()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (draft.editingId == null) R.string.editor_new_title else R.string.editor_edit_title))
                },
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_cancel))
                    }
                },
                actions = {
                    TextButton(onClick = { vm.saveDraft(auth) }, enabled = checksumOk && !vm.busy) {
                        Text(stringResource(R.string.common_save))
                    }
                },
            )
        },
        bottomBar = {
            if (wordsActive) {
                Bip39Keyboard(
                    title = stringResource(R.string.typing_word, input.current + 1),
                    prefix = input.prefix,
                    suggestions = input.suggestions(),
                    enabledLetters = input.enabledLetters(),
                    onLetter = { letter -> if (input.typeLetter(letter)) draft.changed() },
                    onBackspace = {
                        input.backspace()
                        draft.changed()
                    },
                    onSuggestion = { index ->
                        input.choose(index)
                        draft.changed()
                    },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            DebugBanner()
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = draft.label,
                    onValueChange = { draft.label = it.take(60) },
                    label = { Text(stringResource(R.string.label_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (it.isFocused) wordsActive = false },
                )
                Text(stringResource(R.string.word_count_label), style = MaterialTheme.typography.labelLarge)
                WordCountSelector(draft) { activateWords() }
                WordGrid(
                    count = input.wordCount,
                    revision = revision,
                    wordAt = { position -> input.word(position).takeIf { it != SeedInput.EMPTY }?.let(Bip39Wordlist::word) },
                    masked = { false },
                    selected = if (wordsActive) input.current else null,
                    onCellClick = { position ->
                        input.select(position)
                        draft.changed()
                        activateWords()
                    },
                )
                ChecksumStatus(filled = input.filledCount(), total = input.wordCount, complete = complete, valid = checksumOk)
                OptionalFields(draft, onFocused = { wordsActive = false })
                Button(
                    onClick = { vm.saveDraft(auth) },
                    enabled = checksumOk && !vm.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.common_save)) }
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.discard_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    vm.discardDraft()
                }) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.keep_editing)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WordCountSelector(draft: SeedDraft, onChanged: () -> Unit) {
    val counts = Mnemonic.VALID_WORD_COUNTS
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        counts.forEachIndexed { i, count ->
            SegmentedButton(
                selected = draft.input.wordCount == count,
                onClick = {
                    draft.input.setWordCount(count)
                    draft.changed()
                    onChanged()
                },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = counts.size),
                label = { Text(count.toString()) },
            )
        }
    }
}

@Composable
private fun ChecksumStatus(filled: Int, total: Int, complete: Boolean, valid: Boolean) {
    val (text, color) = when {
        !complete -> stringResource(R.string.checksum_incomplete, filled, total) to MaterialTheme.colorScheme.onSurfaceVariant
        valid -> stringResource(R.string.checksum_ok) to MaterialTheme.colorScheme.primary
        else -> stringResource(R.string.checksum_bad) to MaterialTheme.colorScheme.error
    }
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun OptionalFields(draft: SeedDraft, onFocused: () -> Unit) {
    var showPassphrase by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.optional_section),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = draft.showOptional, onCheckedChange = { draft.showOptional = it })
    }
    if (!draft.showOptional) return
    SecureTextField(
        label = stringResource(R.string.bip39_passphrase_label) + " · " + stringResource(R.string.passphrase_hint),
        initial = draft.passphrase.chars,
        masked = !showPassphrase,
        multiLine = false,
        maxLength = EntryCodec.MAX_PASSPHRASE_CHARS,
        onChanged = {
            draft.passphrase.set(it)
            draft.changed()
        },
        onFocused = onFocused,
    )
    TextButton(onClick = { showPassphrase = !showPassphrase }) {
        Text(stringResource(if (showPassphrase) R.string.hide_text else R.string.show_text))
    }
    SecureTextField(
        label = stringResource(R.string.notes_label),
        initial = draft.notes.chars,
        masked = false,
        multiLine = true,
        maxLength = EntryCodec.MAX_NOTES_CHARS,
        onChanged = {
            draft.notes.set(it)
            draft.changed()
        },
        onFocused = onFocused,
    )
}
