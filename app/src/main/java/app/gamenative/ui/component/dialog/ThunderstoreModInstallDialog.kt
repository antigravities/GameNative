package app.gamenative.ui.component.dialog

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.mods.ThunderstoreInstallResult
import app.gamenative.mods.ThunderstoreModInstaller
import app.gamenative.ui.util.SnackbarManager
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun ThunderstoreModInstallDialog(
    visible: Boolean,
    appId: String,
    gameRootDir: File?,
    onDismissRequest: () -> Unit,
) {
    if (!visible) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isInstalling by remember(appId) { mutableStateOf(false) }
    var unityWarningPending by remember(appId) { mutableStateOf(false) }

    val zipPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null || gameRootDir == null) return@rememberLauncherForActivityResult

        val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
        } ?: uri.lastPathSegment ?: "mod.zip"

        isInstalling = true
        scope.launch {
            when (val result = ThunderstoreModInstaller.install(context, appId, gameRootDir, uri, displayName)) {
                is ThunderstoreInstallResult.Success -> {
                    isInstalling = false
                    if (result.unityNoBepInExWarning) {
                        unityWarningPending = true
                    } else {
                        SnackbarManager.show(context.getString(R.string.thunderstore_install_success))
                        onDismissRequest()
                    }
                }
                is ThunderstoreInstallResult.Error -> {
                    isInstalling = false
                    SnackbarManager.show(context.getString(R.string.thunderstore_install_error, result.message))
                    onDismissRequest()
                }
            }
        }
    }

    if (unityWarningPending) {
        AlertDialog(
            onDismissRequest = {
                unityWarningPending = false
                onDismissRequest()
            },
            title = { Text(stringResource(R.string.thunderstore_install_title)) },
            text = { Text(stringResource(R.string.thunderstore_install_unity_no_bepinex_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    unityWarningPending = false
                    onDismissRequest()
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
        return
    }

    LoadingDialog(
        visible = isInstalling,
        progress = -1f,
        message = stringResource(R.string.thunderstore_install_installing),
    )

    if (!isInstalling) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(stringResource(R.string.thunderstore_install_title)) },
            text = {
                Column {
                    Button(onClick = { zipPickerLauncher.launch(arrayOf("*/*")) }) {
                        Text(stringResource(R.string.thunderstore_install_choose_zip))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
