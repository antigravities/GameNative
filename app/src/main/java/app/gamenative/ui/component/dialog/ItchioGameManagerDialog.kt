package app.gamenative.ui.component.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.service.itchio.ItchioApiClient
import app.gamenative.service.itchio.ItchioApiClient.UploadEntry
import app.gamenative.ui.component.LoadingScreen
import app.gamenative.ui.component.topbar.BackButton
import app.gamenative.utils.StorageUtils
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Download-file picker dialog for itch.io games.
 *
 * Mirrors the structure of [GameManagerDialog] used for Steam.
 * Fetches the upload list from the itch.io API when shown, filters to
 * Windows-compatible uploads ([TRAIT_WINDOWS]), and lets the user select
 * which files to download via checkboxes.
 */
@Composable
fun ItchioGameManagerDialog(
    visible: Boolean,
    gameId: Long,
    gameName: String,
    heroImageUrl: String?,
    apiKey: String,
    downloadKeyId: Long?,
    onDownload: (selectedUploads: List<UploadEntry>) -> Unit,
    onDismissRequest: () -> Unit,
) {
    if (!visible) return

    val scrollState = rememberScrollState()

    // Tri-state: null = loading, empty = error/no results, non-empty = ready
    var uploads by remember { mutableStateOf<List<ItchioApiClient.UploadEntry>?>(null) }
    var fetchError by remember { mutableStateOf<String?>(null) }

    // Checked state keyed by upload ID. Only Windows uploads start checked.
    val selectedIds = remember { mutableStateMapOf<Long, Boolean>() }

    LaunchedEffect(visible, gameId, downloadKeyId) {
        // downloadKeyId == null means the DB row (produceState in the caller) hasn't loaded yet.
        // Return early and wait — this effect re-runs once the key arrives.
        if (downloadKeyId == null) return@LaunchedEffect

        // Reset state whenever the dialog (re-)opens or the key changes
        uploads = null
        fetchError = null
        selectedIds.clear()
        scrollState.animateScrollTo(0)

        val result = withContext(Dispatchers.IO) {
            ItchioApiClient.fetchUploads(apiKey, gameId, downloadKeyId)
        }

        result.fold(
            onSuccess = { list ->
                uploads = list
                // Pre-select all Windows uploads; leave others unchecked (disabled).
                list.forEach { upload ->
                    if (TRAIT_WINDOWS in upload.traits) {
                        selectedIds[upload.id] = true
                    } else {
                        selectedIds[upload.id] = false
                    }
                }
            },
            onFailure = { e ->
                fetchError = e.message ?: "Unknown error"
            },
        )
    }

    val totalSelectedBytes by remember(selectedIds.toMap(), uploads) {
        derivedStateOf {
            uploads?.filter { selectedIds[it.id] == true }?.sumOf { it.size } ?: 0L
        }
    }

    val canDownload by remember(selectedIds.toMap(), uploads) {
        derivedStateOf {
            uploads != null && selectedIds.any { (_, checked) -> checked }
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.Start,
        ) {
            // ── Hero image ────────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp),
            ) {
                if (heroImageUrl != null) {
                    CoilImage(
                        modifier = Modifier.fillMaxSize(),
                        imageModel = { heroImageUrl },
                        imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                        loading = { LoadingScreen() },
                        failure = {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.primary,
                            ) {}
                        },
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.primary,
                    ) {}
                }

                // Dark gradient so the title text is readable over any image
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                            ),
                        ),
                )

                Box(
                    modifier = Modifier
                        .padding(20.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                ) {
                    BackButton(onClick = onDismissRequest)
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(20.dp),
                ) {
                    Text(
                        text = gameName,
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold,
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.5f),
                                offset = Offset(0f, 2f),
                                blurRadius = 10f,
                            ),
                        ),
                        color = Color.White,
                    )
                }
            }

            // ── Upload list ───────────────────────────────────────────────────────
            when {
                fetchError != null -> {
                    Text(
                        text = "Could not load files: $fetchError",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }

                uploads == null -> {
                    // Still fetching — show a spinner in the list area
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingScreen()
                    }
                }

                uploads!!.isEmpty() -> {
                    Text(
                        text = "No downloadable files found for this game.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(16.dp),
                    )
                }

                else -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        uploads!!.forEach { upload ->
                            val isWindows = TRAIT_WINDOWS in upload.traits
                            val checked = selectedIds[upload.id] ?: false

                            ListItem(
                                headlineContent = {
                                    Column {
                                        Text(
                                            text = upload.displayName,
                                            color = if (isWindows) {
                                                MaterialTheme.colorScheme.onSurface
                                            } else {
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                            },
                                        )
                                        Text(
                                            text = buildString {
                                                append(StorageUtils.formatBinarySize(upload.size))
                                                if (!isWindows) append(" — not compatible")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                        )
                                    }
                                },
                                trailingContent = {
                                    Checkbox(
                                        checked = checked,
                                        enabled = isWindows,
                                        onCheckedChange = { isChecked ->
                                            selectedIds[upload.id] = isChecked
                                        },
                                    )
                                },
                                modifier = Modifier.clickable(enabled = isWindows) {
                                    selectedIds[upload.id] = !checked
                                },
                            )

                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            )
                        }
                    }
                }
            }

            // ── Footer: total size + Download button ──────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 8.dp, bottom = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(0.5f),
                    text = if (totalSelectedBytes > 0L) {
                        "Download: ${StorageUtils.formatBinarySize(totalSelectedBytes)}"
                    } else {
                        ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                Button(
                    enabled = canDownload,
                    onClick = {
                        val selected = uploads!!.filter { selectedIds[it.id] == true }
                        onDownload(selected)
                    },
                ) {
                    Text("Download")
                }
            }
        }
    }
}

/** itch.io trait tag that marks a Windows-compatible upload. */
private const val TRAIT_WINDOWS = "p_windows"
