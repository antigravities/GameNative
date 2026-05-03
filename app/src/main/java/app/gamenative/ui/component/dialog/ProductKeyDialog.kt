package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.ui.component.dialog.state.ProductKeyDialogState

@Composable
fun ProductKeyDialog(
    state: ProductKeyDialogState,
    onStateChange: (ProductKeyDialogState) -> Unit,
    onActivate: (String) -> Unit,
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
                    text = stringResource(R.string.activate_product),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                )

                // NoExtractOutlinedTextField prevents clipboard extraction, matching the pattern
                // used for other sensitive inputs in this app. singleLine + Password keyboard keeps
                // entry compact and non-predictive; ImeAction.Done dismisses the keyboard.
                NoExtractOutlinedTextField(
                    value = state.code,
                    onValueChange = { onStateChange(state.copy(code = it)) },
                    label = { Text(stringResource(R.string.activate_product_key_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }

                    Button(
                        onClick = { onActivate(state.code) },
                        modifier = Modifier.padding(start = 8.dp),
                        enabled = state.code.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.activate_product_btn))
                    }
                }
            }
        }
    }
}
