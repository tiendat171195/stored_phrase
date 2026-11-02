package io.github.tiendat171195.storedphrase.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tiendat171195.storedphrase.R
import io.github.tiendat171195.storedphrase.core.bip39.Bip39Wordlist

private val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

/**
 * In-app keyboard for BIP39 words. The system IME is never used for seed words, so nothing
 * can be learned, synced or suggested by Samsung Keyboard / Gboard.
 */
@Composable
fun Bip39Keyboard(
    title: String,
    prefix: String,
    suggestions: List<Int>,
    enabledLetters: Set<Char>,
    onLetter: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSuggestion: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(
                    text = "  " + prefix + "▌",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
            ) {
                if (prefix.isNotEmpty() && suggestions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.keyboard_no_match),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
                for (index in suggestions) {
                    SuggestionChip(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSuggestion(index)
                        },
                        label = { Text(Bip39Wordlist.word(index), fontFamily = FontFamily.Monospace) },
                    )
                }
            }
            ROWS.forEachIndexed { rowIndex, row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (rowIndex == 1) Box(Modifier.weight(0.5f))
                    for (letter in row) {
                        Key(
                            label = letter.toString(),
                            enabled = letter in enabledLetters,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onLetter(letter)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowIndex == 1) Box(Modifier.weight(0.5f))
                    if (rowIndex == 2) {
                        val description = stringResource(R.string.keyboard_backspace)
                        Key(
                            label = "⌫",
                            enabled = true,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onBackspace()
                            },
                            modifier = Modifier
                                .weight(1.5f)
                                .semantics { contentDescription = description },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Key(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = if (enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .height(46.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
        }
    }
}
