package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.gamenative.R
import `in`.dragonbra.javasteam.enums.EUCMFilePrivacyState

private val PRIVACY_OPTIONS = listOf(
    EUCMFilePrivacyState.Public,
    EUCMFilePrivacyState.FriendsOnly,
    EUCMFilePrivacyState.Private,
)

private fun labelFor(privacy: EUCMFilePrivacyState): Int = when (privacy) {
    EUCMFilePrivacyState.Public -> R.string.screenshot_upload_privacy_public
    EUCMFilePrivacyState.FriendsOnly -> R.string.screenshot_upload_privacy_friends_only
    else -> R.string.screenshot_upload_privacy_private
}

/** Prompts for a caption and visibility before uploading a screenshot to the user's Steam library. */
@Composable
fun ScreenshotUploadDialog(
    onDismiss: () -> Unit,
    onUpload: (caption: String, privacy: EUCMFilePrivacyState) -> Unit,
) {
    var caption by remember { mutableStateOf("") }
    // Private by default so an upload never surprises anyone by being public.
    var privacy by remember { mutableStateOf(EUCMFilePrivacyState.Private) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.screenshot_upload_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    label = { Text(text = stringResource(R.string.screenshot_upload_caption_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(modifier = Modifier.selectableGroup().padding(top = 8.dp)) {
                    PRIVACY_OPTIONS.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .selectable(
                                    selected = option == privacy,
                                    onClick = { privacy = option },
                                    role = Role.RadioButton,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == privacy, onClick = null)
                            Text(
                                text = stringResource(labelFor(option)),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onUpload(caption, privacy)
                onDismiss()
            }) {
                Text(text = stringResource(R.string.screenshot_upload_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.cancel)) }
        },
    )
}
