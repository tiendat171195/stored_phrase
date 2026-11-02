package io.github.tiendat171195.storedphrase.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tiendat171195.storedphrase.R
import io.github.tiendat171195.storedphrase.security.BiometricStatus
import io.github.tiendat171195.storedphrase.security.DeviceReport
import io.github.tiendat171195.storedphrase.ui.AppViewModel
import io.github.tiendat171195.storedphrase.ui.LocalAuthenticator
import io.github.tiendat171195.storedphrase.ui.components.CheckRow
import io.github.tiendat171195.storedphrase.ui.components.CheckState
import io.github.tiendat171195.storedphrase.ui.components.DebugBanner
import io.github.tiendat171195.storedphrase.ui.components.NoticeCard

private enum class Verdict { READY_KNOX, READY_STRONGBOX, TEE_DEBUG, NEED_LOCK_SCREEN, UNSUPPORTED }

private fun verdict(report: DeviceReport, allowTee: Boolean): Verdict = when {
    !report.isDeviceSecure -> Verdict.NEED_LOCK_SCREEN
    report.strongBoxReady && report.isSamsung -> Verdict.READY_KNOX
    report.strongBoxReady -> Verdict.READY_STRONGBOX
    allowTee -> Verdict.TEE_DEBUG
    else -> Verdict.UNSUPPORTED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceCheckScreen(vm: AppViewModel) {
    val auth = LocalAuthenticator.current
    val report = vm.deviceReport
    val canGoBack = vm.backStack.size > 1
    LaunchedEffect(Unit) { if (vm.deviceReport == null) vm.runDeviceCheck() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.device_check_title)) },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = { vm.back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
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
            if (vm.deviceCheckRunning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (report == null) {
                    Text(stringResource(R.string.check_running))
                } else {
                    DeviceChecks(report)
                    HorizontalDivider()
                    val verdict = verdict(report, vm.allowTeeFallback)
                    VerdictCard(verdict)
                    if (!vm.vaultExists) {
                        if (verdict != Verdict.NEED_LOCK_SCREEN && verdict != Verdict.UNSUPPORTED) {
                            NoticeCard(
                                text = stringResource(R.string.create_vault_warning),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                            )
                        }
                        Button(
                            onClick = { vm.createVault(auth) },
                            enabled = !vm.busy && verdict != Verdict.NEED_LOCK_SCREEN && verdict != Verdict.UNSUPPORTED,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.create_vault))
                        }
                    }
                    OutlinedButton(
                        onClick = { vm.runDeviceCheck() },
                        enabled = !vm.deviceCheckRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.rerun_check))
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceChecks(report: DeviceReport) {
    CheckRow(
        title = stringResource(R.string.check_strongbox_feature),
        state = if (report.hasStrongBoxFeature) CheckState.OK else CheckState.FAIL,
    )
    CheckRow(
        title = stringResource(R.string.check_lock_screen),
        state = if (report.isDeviceSecure) CheckState.OK else CheckState.FAIL,
    )
    CheckRow(
        title = stringResource(R.string.check_biometric),
        state = when (report.biometric) {
            BiometricStatus.AVAILABLE -> CheckState.OK
            else -> CheckState.WARN
        },
        detail = when (report.biometric) {
            BiometricStatus.AVAILABLE -> null
            BiometricStatus.NOT_ENROLLED -> stringResource(R.string.check_biometric_not_enrolled)
            BiometricStatus.UNAVAILABLE -> stringResource(R.string.check_biometric_unavailable)
        },
    )
    CheckRow(
        title = stringResource(R.string.check_roundtrip),
        state = if (report.strongBoxRoundTrip.ok && report.strongBoxRoundTrip.isStrongBox) CheckState.OK else CheckState.FAIL,
        detail = report.strongBoxRoundTrip.detail,
    )
    val protectedKey = report.protectedKey
    CheckRow(
        title = stringResource(R.string.check_protected_key),
        state = when {
            protectedKey == null -> CheckState.FAIL
            !protectedKey.ok || !protectedKey.isStrongBox -> CheckState.FAIL
            report.protectedKeyConfig?.unlockedDeviceRequired == false -> CheckState.WARN
            else -> CheckState.OK
        },
        detail = when {
            protectedKey == null -> stringResource(R.string.check_needs_lock_screen)
            protectedKey.ok && report.protectedKeyConfig?.unlockedDeviceRequired == false ->
                stringResource(R.string.check_udr_unsupported)
            else -> protectedKey.detail
        },
    )
    Text(
        text = stringResource(R.string.device_info, report.manufacturer, report.model, report.androidRelease, report.sdkInt),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun VerdictCard(verdict: Verdict) {
    val (text, color) = when (verdict) {
        Verdict.READY_KNOX -> stringResource(R.string.verdict_knox) to MaterialTheme.colorScheme.primaryContainer
        Verdict.READY_STRONGBOX -> stringResource(R.string.verdict_strongbox_other) to MaterialTheme.colorScheme.primaryContainer
        Verdict.TEE_DEBUG -> stringResource(R.string.verdict_tee_debug) to MaterialTheme.colorScheme.errorContainer
        Verdict.NEED_LOCK_SCREEN -> stringResource(R.string.verdict_need_lock_screen) to MaterialTheme.colorScheme.errorContainer
        Verdict.UNSUPPORTED -> stringResource(R.string.verdict_unsupported) to MaterialTheme.colorScheme.errorContainer
    }
    NoticeCard(text = text, color = color)
}
