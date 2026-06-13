package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.SummarizationResult
import com.google.mlkit.genai.summarization.Summarizer
import com.google.mlkit.genai.summarization.SummarizerOptions
import com.google.mlkit.genai.summarization.SummarizerOptions.InputType
import com.google.mlkit.genai.summarization.SummarizerOptions.Language
import com.google.mlkit.genai.summarization.SummarizerOptions.OutputType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface SummaryState {
    object Idle : SummaryState
    // status is shown to the user while inference is running (e.g. "Summarizing block 2 of 5…")
    data class Loading(val status: String) : SummaryState
    data class Success(val text: String) : SummaryState
    data class Error(val message: String) : SummaryState
}

// empirical character limit for the summarization API
private const val BLOCK_CHARS = 7_860

// Blocks are selected from the end of the log (most recent content);
// at most MAX_BLOCKS inference calls are made before the final resummarization pass.
private const val MAX_BLOCKS = 10

// BUSY is transient — retry up to 3 times with a fixed 2 s delay between attempts.
// ListenableFuture.get() wraps the original exception in ExecutionException, so we unwrap.
private suspend fun runInferenceWithRetry(
    summarizer: Summarizer,
    request: SummarizationRequest,
): SummarizationResult {
    repeat(3) { attempt ->
        try {
            return summarizer.runInference(request).get()
        } catch (e: java.util.concurrent.ExecutionException) {
            val cause = e.cause
            if (cause is GenAiException && cause.errorCode == GenAiException.ErrorCode.BUSY && attempt < 2) {
                delay(2_000)
            } else {
                throw e
            }
        }
    }
    error("unreachable")
}

// Collapse consecutive identical lines (uniq-style) before summarizing.
// Non-adjacent duplicates are kept — they may represent the same error at different stages.
private fun deduplicateAdjacentLines(text: String): String {
    var prev: String? = null
    return text.lines()
        .filter { line -> (line != prev).also { prev = line } }
        .joinToString("\n")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogDialog(
    visible: Boolean = true,
    fileName: String,
    fileText: String,
    onSave: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    if (visible) {
        val context = LocalContext.current
        // rememberCoroutineScope() is tied to the Main dispatcher by default,
        // so state assignments outside withContext blocks land on the UI thread.
        val scope = rememberCoroutineScope()
        var summaryState: SummaryState by remember { mutableStateOf(SummaryState.Idle) }
        val scrollState = rememberScrollState()

        fun summarize() {
            scope.launch {
                summaryState = SummaryState.Loading("Preparing…")
                // All blocking ListenableFuture.get() calls run on IO; progress updates
                // switch back to Main so Compose state is updated from the right thread.
                summaryState = withContext(Dispatchers.IO) {
                    try {
                        val options = SummarizerOptions.builder(context)
                            .setInputType(InputType.ARTICLE)
                            .setOutputType(OutputType.THREE_BULLETS)
                            .setLanguage(Language.ENGLISH)
                            .build()
                        val summarizer = Summarization.getClient(options)
                        try {
                            val status = summarizer.checkFeatureStatus().get()
                            if (status == FeatureStatus.UNAVAILABLE) {
                                return@withContext SummaryState.Error(
                                    "AI summarization is not supported on this device.",
                                )
                            }
                            // Trigger model download if it hasn't been fetched yet, then wait.
                            if (status == FeatureStatus.DOWNLOADABLE || status == FeatureStatus.DOWNLOADING) {
                                withContext(Dispatchers.Main) {
                                    summaryState = SummaryState.Loading("Downloading AI model…")
                                }
                                val deferred = CompletableDeferred<Unit>()
                                summarizer.downloadFeature(object : DownloadCallback {
                                    override fun onDownloadStarted(bytesToDownload: Long) {}
                                    override fun onDownloadProgress(totalBytesDownloaded: Long) {}
                                    override fun onDownloadFailed(e: GenAiException) {
                                        deferred.completeExceptionally(e)
                                    }
                                    override fun onDownloadCompleted() { deferred.complete(Unit) }
                                })
                                deferred.await()
                            }

                            // Phase 1: collapse consecutive identical lines to reduce token count.
                            // Wine debug logs repeat the same fixme/err lines thousands of times.
                            val dedupedText = deduplicateAdjacentLines(fileText)

                            // Phase 2: summarize the last MAX_BLOCKS chunks of the deduplicated log.
                            val blocks = dedupedText.chunked(BLOCK_CHARS).takeLast(MAX_BLOCKS)
                            val blockSummaries = mutableListOf<String>()
                            for ((i, block) in blocks.withIndex()) {
                                withContext(Dispatchers.Main) {
                                    summaryState = SummaryState.Loading("Summarizing block ${i + 1} of ${blocks.size}…")
                                }
                                blockSummaries += runInferenceWithRetry(
                                summarizer,
                                SummarizationRequest.builder(block).build(),
                            ).summary
                            }

                            // Phase 3: if there were multiple blocks, resummarize their summaries
                            // into a single unified result.
                            val finalSummary = if (blockSummaries.size == 1) {
                                blockSummaries[0]
                            } else {
                                withContext(Dispatchers.Main) {
                                    summaryState = SummaryState.Loading("Building final summary…")
                                }
                                runInferenceWithRetry(
                                    summarizer,
                                    SummarizationRequest.builder(blockSummaries.joinToString("\n")).build(),
                                ).summary
                            }

                            SummaryState.Success(finalSummary)
                        } finally {
                            summarizer.close()
                        }
                    } catch (e: java.util.concurrent.ExecutionException) {
                        // Include the error code number so we can identify new quota/rate codes.
                        val cause = e.cause
                        val detail = if (cause is GenAiException) {
                            "error ${cause.errorCode}: ${cause.localizedMessage}"
                        } else {
                            cause?.localizedMessage ?: e.localizedMessage
                        }
                        SummaryState.Error("Summarization failed: $detail")
                    } catch (e: Exception) {
                        SummaryState.Error("Summarization failed: ${e.localizedMessage}")
                    }
                }
            }
        }

        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false,
            ),
            content = {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = {
                                Text(
                                    text = fileName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            navigationIcon = {
                                IconButton(
                                    onClick = onDismissRequest,
                                    content = { Icon(Icons.Default.Close, null) },
                                )
                            },
                            actions = {
                                val loading = summaryState as? SummaryState.Loading
                                IconButton(
                                    onClick = ::summarize,
                                    enabled = summaryState is SummaryState.Idle,
                                ) {
                                    if (loading != null) {
                                        // Replace icon with spinner; progress label is in loading.status
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(Icons.Default.Summarize, contentDescription = "Summarize with AI")
                                    }
                                }
                                IconButton(
                                    onClick = onSave,
                                    content = { Icon(Icons.Default.Save, null) },
                                )
                            },
                        )
                    },
                ) { paddingValues ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(
                                top = app.gamenative.utils.PaddingUtils.statusBarAwarePadding().calculateTopPadding() + paddingValues.calculateTopPadding(),
                                bottom = 24.dp + paddingValues.calculateBottomPadding(),
                                start = paddingValues.calculateStartPadding(LayoutDirection.Ltr),
                                end = paddingValues.calculateEndPadding(LayoutDirection.Ltr),
                            ),
                    ) {
                        Text(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 6.dp),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            fontSize = 12.sp,
                            text = fileText,
                        )
                    }
                }

                // AI-generated summary — shown as an overlay AlertDialog on top of the log
                if (summaryState is SummaryState.Success) {
                    AlertDialog(
                        onDismissRequest = { summaryState = SummaryState.Idle },
                        title = { Text("Log Summary") },
                        text = { Text((summaryState as SummaryState.Success).text) },
                        confirmButton = {
                            TextButton(onClick = { summaryState = SummaryState.Idle }) { Text("OK") }
                        },
                    )
                }

                // Error dialog — surfaces unsupported device, download failures, or inference errors
                if (summaryState is SummaryState.Error) {
                    AlertDialog(
                        onDismissRequest = { summaryState = SummaryState.Idle },
                        title = { Text("Summarization Unavailable") },
                        text = { Text((summaryState as SummaryState.Error).message) },
                        confirmButton = {
                            TextButton(onClick = { summaryState = SummaryState.Idle }) { Text("OK") }
                        },
                    )
                }
            },
        )
    }
}
