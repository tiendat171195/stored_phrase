package io.github.tiendat171195.storedphrase.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tiendat171195.storedphrase.R
import io.github.tiendat171195.storedphrase.ui.AppViewModel
import io.github.tiendat171195.storedphrase.ui.LocalAuthenticator
import io.github.tiendat171195.storedphrase.ui.QuizSession
import io.github.tiendat171195.storedphrase.ui.components.Bip39Keyboard
import io.github.tiendat171195.storedphrase.ui.components.DebugBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuizScreen(vm: AppViewModel) {
    val quiz = vm.quiz ?: return
    val auth = LocalAuthenticator.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.quiz_title)) },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
                    }
                },
            )
        },
        bottomBar = {
            if (!quiz.finished) {
                Bip39Keyboard(
                    title = stringResource(R.string.quiz_question, quiz.positions[quiz.step] + 1),
                    prefix = quiz.prefix,
                    suggestions = quiz.suggestions(),
                    enabledLetters = quiz.enabledLetters(),
                    onLetter = { quiz.typeLetter(it) },
                    onBackspace = { quiz.backspace() },
                    onSuggestion = { quiz.answer(it) },
                )
            }
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
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!quiz.finished) {
                    Questions(quiz)
                } else {
                    Result(
                        quiz = quiz,
                        busy = vm.busy,
                        onDone = { vm.completeQuiz(auth) },
                        onRetry = { vm.retryQuiz() },
                        onClose = { vm.back() },
                    )
                }
            }
        }
    }
}

@Composable
private fun Questions(quiz: QuizSession) {
    Text(stringResource(R.string.quiz_intro), style = MaterialTheme.typography.bodyMedium)
    LinearProgressIndicator(
        progress = { quiz.step.toFloat() / quiz.positions.size },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = stringResource(R.string.quiz_progress, quiz.step + 1, quiz.positions.size),
        style = MaterialTheme.typography.labelLarge,
    )
    Text(
        text = stringResource(R.string.quiz_question, quiz.positions[quiz.step] + 1),
        style = MaterialTheme.typography.headlineSmall,
    )
}

@Composable
private fun Result(quiz: QuizSession, busy: Boolean, onDone: () -> Unit, onRetry: () -> Unit, onClose: () -> Unit) {
    val passed = quiz.passed
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            imageVector = if (passed) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = if (passed) {
                stringResource(R.string.quiz_success)
            } else {
                stringResource(R.string.quiz_fail, quiz.mismatches.joinToString(", "))
            },
            style = MaterialTheme.typography.titleMedium,
        )
    }
    if (passed) {
        Button(onClick = onDone, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_done))
        }
    } else {
        Button(onClick = onRetry, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_retry))
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_close))
        }
    }
}
