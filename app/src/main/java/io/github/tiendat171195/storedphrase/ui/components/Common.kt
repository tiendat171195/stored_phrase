package io.github.tiendat171195.storedphrase.ui.components

import android.os.Build
import android.security.keystore.KeyProperties
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tiendat171195.storedphrase.BuildConfig
import io.github.tiendat171195.storedphrase.R
import io.github.tiendat171195.storedphrase.security.VaultCrypto

@Composable
fun DebugBanner(modifier: Modifier = Modifier) {
    if (!BuildConfig.DEBUG) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.debug_banner),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/** Where the vault keys live: Knox Vault / StrongBox, or TEE on debug builds. */
@Composable
fun SecurityBadge(report: VaultCrypto.KeyReport?, modifier: Modifier = Modifier) {
    if (report == null) return
    val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    val (label, good) = when {
        report.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX && samsung ->
            stringResource(R.string.badge_knox) to true
        report.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX ->
            stringResource(R.string.badge_strongbox) to true
        else -> stringResource(R.string.badge_tee) to false
    }
    AssistChip(
        onClick = {},
        label = { Text(label) },
        leadingIcon = {
            Icon(
                imageVector = if (good) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
        modifier = modifier,
    )
}

enum class CheckState { OK, WARN, FAIL }

@Composable
fun CheckRow(title: String, state: CheckState, detail: String? = null) {
    val (icon, tint) = when (state) {
        CheckState.OK -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
        CheckState.WARN -> Icons.Filled.Info to MaterialTheme.colorScheme.tertiary
        CheckState.FAIL -> Icons.Filled.Warning to MaterialTheme.colorScheme.error
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!detail.isNullOrBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun NoticeCard(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.secondaryContainer) {
    Surface(color = color, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}
