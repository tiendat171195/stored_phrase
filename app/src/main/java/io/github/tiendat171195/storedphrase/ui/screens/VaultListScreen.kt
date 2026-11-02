package io.github.tiendat171195.storedphrase.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.tiendat171195.storedphrase.R
import io.github.tiendat171195.storedphrase.core.vault.IndexEntry
import io.github.tiendat171195.storedphrase.ui.AppViewModel
import io.github.tiendat171195.storedphrase.ui.components.DebugBanner
import io.github.tiendat171195.storedphrase.ui.components.SecurityBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultListScreen(vm: AppViewModel) {
    val entries = vm.index?.entries.orEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { vm.lock() }) {
                        Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.lock_now))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            DebugBanner()
            SecurityBadge(vm.keyReport, modifier = Modifier.padding(horizontal = 16.dp))
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.empty_vault),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    items(entries, key = { it.id }) { entry -> EntryRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: IndexEntry, modifier: Modifier = Modifier) {
    val words = pluralStringResource(R.plurals.words_count, entry.wordCount, entry.wordCount)
    val extra = if (entry.hasPassphrase) " · " + stringResource(R.string.has_passphrase_short) else ""
    val verified = entry.verifiedAt?.let {
        stringResource(R.string.verified_at, DateUtils.getRelativeTimeSpanString(it).toString())
    } ?: stringResource(R.string.verified_never)
    ListItem(
        headlineContent = { Text(entry.label) },
        supportingContent = { Text("$words$extra\n$verified") },
        modifier = modifier,
    )
}
