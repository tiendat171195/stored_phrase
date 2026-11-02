package io.github.tiendat171195.storedphrase.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val MASK = "••••••"

/**
 * Numbered grid of seed words.
 *
 * @param wordAt word text for a position, or null when the slot is empty.
 * @param masked whether a position is shown as dots.
 * @param revision changes whenever the data behind [wordAt] changes, so the grid recomposes.
 */
@Composable
fun WordGrid(
    count: Int,
    wordAt: (Int) -> String?,
    masked: (Int) -> Boolean,
    modifier: Modifier = Modifier,
    revision: Int = 0,
    selected: Int? = null,
    columns: Int = 3,
    onCellClick: ((Int) -> Unit)? = null,
) {
    key(revision) {}
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (rowStart in 0 until count step columns) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                for (position in rowStart until rowStart + columns) {
                    if (position < count) {
                        WordCell(
                            number = position + 1,
                            text = if (masked(position)) MASK else wordAt(position).orEmpty(),
                            isSelected = position == selected,
                            onClick = onCellClick?.let { { it(position) } },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Box(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun WordCell(
    number: Int,
    text: String,
    isSelected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val border = if (isSelected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        border = border,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = modifier
            .height(40.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(
                text = "$number.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = " $text",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}
