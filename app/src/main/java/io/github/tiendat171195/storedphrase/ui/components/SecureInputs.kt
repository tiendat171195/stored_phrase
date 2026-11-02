package io.github.tiendat171195.storedphrase.ui.components

import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Text field for secrets that must be typed with the system keyboard (BIP39 passphrase, notes).
 * A plain EditText gives full control over IME flags:
 * - IME_FLAG_NO_PERSONALIZED_LEARNING: keyboards must not learn or sync what is typed,
 * - TYPE_TEXT_FLAG_NO_SUGGESTIONS: no suggestion strip,
 * - autofill disabled and isSaveEnabled = false: the text never goes into saved instance state.
 * Changes are reported as a CharSequence so callers can copy chars without creating Strings.
 */
@Composable
fun SecureTextField(
    label: String,
    initial: CharArray,
    masked: Boolean,
    multiLine: Boolean,
    maxLength: Int,
    onChanged: (CharSequence) -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
) {
    val latestOnChanged by rememberUpdatedState(onChanged)
    val latestOnFocused by rememberUpdatedState(onFocused)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            AndroidView(
                factory = { context ->
                    EditText(context).apply {
                        val variation = if (multiLine) {
                            InputType.TYPE_TEXT_VARIATION_NORMAL or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        } else {
                            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        }
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or variation
                        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                        isSaveEnabled = false
                        // No copy / cut / share menu for secrets.
                        isLongClickable = false
                        customSelectionActionModeCallback = NoCopyActionMode
                        filters = arrayOf(InputFilter.LengthFilter(maxLength))
                        if (multiLine) {
                            minLines = 2
                            maxLines = 6
                        } else {
                            maxLines = 1
                        }
                        background = null
                        setPadding(32, 24, 32, 24)
                        setText(initial, 0, initial.size)
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                            override fun afterTextChanged(s: Editable) {
                                latestOnChanged(s)
                            }
                        })
                        setOnFocusChangeListener { _, hasFocus -> if (hasFocus) latestOnFocused() }
                    }
                },
                update = { editText ->
                    val method = if (masked) PasswordTransformationMethod.getInstance() else null
                    if (editText.transformationMethod != method) editText.transformationMethod = method
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Refuses to open the text selection menu, so secrets cannot be copied to the clipboard. */
private object NoCopyActionMode : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = false
    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false
    override fun onDestroyActionMode(mode: ActionMode) = Unit
}

/** Button-like surface that reports press and release, for "hold to show" interactions. */
@Composable
fun HoldToShow(
    label: String,
    onPressedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latest by rememberUpdatedState(onPressedChange)
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    latest(true)
                    try {
                        tryAwaitRelease()
                    } finally {
                        latest(false)
                    }
                },
            )
        },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
