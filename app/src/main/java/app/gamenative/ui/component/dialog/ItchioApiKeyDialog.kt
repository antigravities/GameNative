package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.ui.component.NoExtractOutlinedTextField

/**
 * Dialog that collects an itch.io API key from the user, validates it, and
 * provides a link to the itch.io API key settings page so they can generate one.
 */
@Composable
fun ItchioApiKeyDialog(
    visible: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (apiKey: String) -> Unit,
    onOpenApiKeyPage: () -> Unit,
) {
    if (!visible) return

    // Track the key locally inside the dialog so it resets each time it opens
    var apiKey by rememberSaveable { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        // Prevent dismissal mid-request so the coroutine can complete cleanly
        properties = DialogProperties(
            dismissOnBackPress = !isLoading,
            dismissOnClickOutside = !isLoading,
        ),
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
                    text = stringResource(R.string.itchio_login_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                Text(
                    text = stringResource(R.string.itchio_login_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )

                // Link to the itch.io API key generation page
                TextButton(
                    onClick = onOpenApiKeyPage,
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    Text(stringResource(R.string.itchio_login_generate_key))
                }

                // NoExtractOutlinedTextField suppresses clipboard extraction for sensitive input.
                // Password keyboard type disables autocorrect/suggestions on most devices.
                NoExtractOutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.itchio_login_api_key_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    enabled = !isLoading,
                )

                // Show validation error below the field when present
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isLoading,
                    ) {
                        Text(stringResource(R.string.cancel))
                    }

                    // Show a spinner in place of the button while the network request is in flight
                    Box(modifier = Modifier.padding(start = 8.dp)) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(36.dp))
                        } else {
                            Button(
                                onClick = { onConfirm(apiKey) },
                                enabled = apiKey.isNotBlank(),
                            ) {
                                Text(stringResource(R.string.itchio_login_connect))
                            }
                        }
                    }
                }
            }
        }
    }
}
