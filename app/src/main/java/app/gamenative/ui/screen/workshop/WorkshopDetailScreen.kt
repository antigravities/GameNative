package app.gamenative.ui.screen.workshop

import android.graphics.drawable.LevelListDrawable
import android.text.Html
import android.text.method.LinkMovementMethod
import android.widget.TextView
import coil.imageLoader
import coil.request.ImageRequest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gamenative.service.SteamService
import app.gamenative.ui.model.WorkshopBrowserViewModel
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.workshop.WorkshopBrowser
import app.gamenative.workshop.WorkshopItemDetail
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.launch
import org.kefirsf.bb.BBProcessorFactory
import org.kefirsf.bb.TextProcessor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkshopDetailScreen(
    appId: Int,
    publishedFileId: Long,
    onBack: () -> Unit,
) {
    val processor = remember<TextProcessor> {
        BBProcessorFactory.getInstance().create()
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ViewModel reused for subscribe/unsubscribe action (3-step flow inside).
    val viewModel: WorkshopBrowserViewModel = viewModel(
        key = "workshop-detail-$appId",
        factory = WorkshopBrowserViewModel.factory(appId),
    )
    val vmState by viewModel.state.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()

    var detail by remember { mutableStateOf<WorkshopItemDetail?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var isSubscribed by remember { mutableStateOf(false) }

    val inProgress = publishedFileId in vmState.subscribeInProgress

    LaunchedEffect(publishedFileId) {
        loadFailed = false
        val client = SteamService.instance?.steamClient
        if (client == null) {
            loadFailed = true
            return@LaunchedEffect
        }
        val results = WorkshopBrowser.getDetails(listOf(publishedFileId), client)
        if (results.isEmpty()) {
            loadFailed = true
            return@LaunchedEffect
        }
        detail = results.first()
        val subMap = WorkshopBrowser.areFilesInSubscriptionList(appId, listOf(publishedFileId), client)
        isSubscribed = subMap[publishedFileId] == true
    }

    // Mirror ViewModel's subscribed-state changes into the local flag (for the toggle).
    LaunchedEffect(vmState.subscribedIds) {
        if (publishedFileId in vmState.subscribedIds) isSubscribed = true
        else if (detail != null) {
            // ViewModel removed it → reflect locally.
            // Note: ViewModel removes from subscribedIds on successful unsubscribe.
            isSubscribed = false
        }
    }

    LaunchedEffect(toast) {
        val msg = toast ?: return@LaunchedEffect
        scope.launch {
            SnackbarManager.show(msg.text)
            viewModel.consumeToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.item?.title ?: "Workshop item") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        val current = detail
        when {
            loadFailed -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Failed to load Workshop item",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            current == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (current.item.previewUrl.isNotEmpty()) {
                        CoilImage(
                            imageModel = { current.item.previewUrl },
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f),
                            imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                        )
                    }

                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = current.item.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${(current.voteScore * 100).toInt()}% rating",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "${current.subscriberCount} subscribers",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (current.tags.isNotEmpty()) {
                            LazyRow(
                                modifier = Modifier.padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(items = current.tags) { tag ->
                                    AssistChip(onClick = {}, label = { Text(tag) })
                                }
                            }
                        }

                        SubscribeButton(
                            isSubscribed = isSubscribed,
                            inProgress = inProgress,
                            onSubscribe = { viewModel.subscribe(publishedFileId, context) },
                            onUnsubscribe = { viewModel.unsubscribe(publishedFileId, context) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                        )

                        if (current.description.isNotEmpty()) {
                            Text(
                                text = "Description",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                            )
                            AndroidView(
                                factory = { context ->
                                    TextView(context).apply {
                                        movementMethod = LinkMovementMethod.getInstance()
                                    }
                                },
                                update = { textView ->
                                    // ImageGetter is called synchronously per <img> tag; we return a
                                    // placeholder immediately and fill it in once Coil finishes loading.
                                    val imageGetter = Html.ImageGetter { source ->
                                        val placeholder = LevelListDrawable()
                                        placeholder.setBounds(0, 0, 0, 0)
                                        textView.context.imageLoader.enqueue(
                                            ImageRequest.Builder(textView.context)
                                                .data(source)
                                                .target { drawable ->
                                                    // Scale to the TextView's width; fall back to intrinsic
                                                    // size if the view hasn't been laid out yet.
                                                    val w = if (textView.width > 0) textView.width else drawable.intrinsicWidth
                                                    val h = if (drawable.intrinsicWidth > 0)
                                                        (w.toFloat() / drawable.intrinsicWidth * drawable.intrinsicHeight).toInt().coerceAtLeast(1)
                                                    else drawable.intrinsicHeight.coerceAtLeast(1)
                                                    drawable.setBounds(0, 0, w, h)
                                                    placeholder.addLevel(0, 0, drawable)
                                                    placeholder.setBounds(0, 0, w, h)
                                                    // Re-set text to trigger re-layout with the now-sized drawable.
                                                    textView.text = textView.text
                                                }
                                                .build()
                                        )
                                        placeholder
                                    }
                                    textView.text = Html.fromHtml(
                                        processor.process(current.description),
                                        Html.FROM_HTML_MODE_COMPACT,
                                        imageGetter,
                                        null,
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscribeButton(
    isSubscribed: Boolean,
    inProgress: Boolean,
    onSubscribe: () -> Unit,
    onUnsubscribe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isSubscribed) {
        OutlinedButton(
            onClick = onUnsubscribe,
            enabled = !inProgress,
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
        ) {
            if (inProgress) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            } else {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text("Unsubscribe")
        }
    } else {
        Button(
            onClick = onSubscribe,
            enabled = !inProgress,
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            if (inProgress) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text("Subscribe")
        }
    }
}
