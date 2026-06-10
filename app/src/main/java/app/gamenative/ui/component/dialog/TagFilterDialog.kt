package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.SteamTag
import app.gamenative.ui.component.OptionListItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalOffer

@Composable
fun TagFilterDialog(
    availableTags: List<SteamTag>,
    selectedTagIds: Set<Int>,
    onTagToggled: (Int) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Local search state — reset each time the dialog is opened via rememberSaveable scoping.
    var search by rememberSaveable { mutableStateOf("") }

    // Filter the tag list by the user's search query (case-insensitive).
    val visibleTags = if (search.isBlank()) availableTags
    else availableTags.filter { it.name.contains(search, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_tag_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text(stringResource(R.string.library_tag_dialog_search)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                )

                // LazyColumn capped to ~60% of screen height so the dialog doesn't overfill.
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(visibleTags, key = { it.id }) { tag ->
                        OptionListItem(
                            text = tag.name,
                            selected = tag.id in selectedTagIds,
                            onClick = { onTagToggled(tag.id) },
                            icon = Icons.Default.LocalOffer,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // Show a hint when no tags are loaded yet (e.g., first run, offline).
                    if (visibleTags.isEmpty()) {
                        item {
                            Text(
                                text = if (availableTags.isEmpty())
                                    stringResource(R.string.library_tag_dialog_loading)
                                else
                                    stringResource(R.string.library_tag_dialog_no_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.library_tag_dialog_done))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onClear()
                onDismiss()
            }) {
                Text(stringResource(R.string.library_tag_dialog_clear))
            }
        },
    )
}
