package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.ui.component.dialog.state.CategoryDialogState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddToCategoryDialog(
    state: CategoryDialogState,
    onStateChange: (CategoryDialogState) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.visible) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            ) {
                Text(
                    text = stringResource(R.string.category_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                )

                OutlinedTextField(
                    value = state.input,
                    onValueChange = { onStateChange(state.copy(input = it)) },
                    label = { Text(stringResource(R.string.category_dialog_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    singleLine = true,
                )

                // Categories the game already belongs to — shown as removable InputChips.
                // Tapping the × icon removes the game from that category immediately.
                if (state.currentCategories.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.category_dialog_current),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        state.currentCategories.forEach { category ->
                            InputChip(
                                selected = true,
                                onClick = { onRemove(category) },
                                label = { Text(category) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.category_dialog_remove_desc),
                                        modifier = Modifier.size(InputChipDefaults.AvatarSize),
                                    )
                                },
                            )
                        }
                    }
                }

                // All existing categories shown as plain suggestion chips.
                // Tapping one fills the text field so the user can quickly add to it.
                if (state.existingCategories.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.category_dialog_existing),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        state.existingCategories.forEach { category ->
                            SuggestionChip(
                                onClick = { onStateChange(state.copy(input = category)) },
                                label = { Text(category) },
                            )
                        }
                    }
                } else if (state.currentCategories.isEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }

                    Button(
                        onClick = { onAdd(state.input.trim()) },
                        modifier = Modifier.padding(start = 8.dp),
                        enabled = state.input.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.category_dialog_add_btn))
                    }
                }
            }
        }
    }
}
