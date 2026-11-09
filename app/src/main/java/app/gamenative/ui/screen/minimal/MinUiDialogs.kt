package app.gamenative.ui.screen.minimal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import app.gamenative.R
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/*
 * Full-screen MinUI overlays. Render them as a SIBLING of the screen's key-handling root (not
 * a child), otherwise the screen's onPreviewKeyEvent sees the keys first. Each grabs focus on
 * show and handles its own buttons, back, and taps.
 */

/**
 * Full-screen dialog window for MinUI overlays that must sit above navigation. A dialog is its
 * own window, so the activity's immersive mode doesn't reach it: hide the bars here too.
 */
@Composable
private fun MinUiDialogWindow(
    onDismissRequest: () -> Unit,
    dismissOnBackPress: Boolean,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = false,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.let {
                WindowCompat.getInsetsController(it, it.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose {}
        }
        content()
    }
}

/** Black scrim that swallows taps so nothing underneath reacts. */
@Composable
private fun MinUiScrim(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MinUiColors.Background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) { content() }
}

/** A yes/no question: A confirms, B (or back) cancels; both pills are tappable. */
@Composable
fun MinUiConfirm(
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val m = rememberMinUiMetrics()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(onBack = onDismiss)
    MinUiScrim(
        modifier = Modifier
            .focusRequester(focus)
            .minUiKeys(onA = onConfirm, onB = onDismiss)
            .focusable(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            MinUiTopBar()
            MinUiMessage(message, Modifier.weight(1f).fillMaxWidth())
            MinUiHintBar(
                left = listOf(MinUiHint("B", "CANCEL", onDismiss)),
                right = listOf(MinUiHint("A", confirmLabel, onConfirm)),
                modifier = Modifier.height(m.rowHeight),
            )
        }
    }
}

/** A short MinUI menu: up/down + A (or tap) picks an item, B (or back) closes. */
@Composable
fun MinUiMenu(
    title: String,
    items: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    val m = rememberMinUiMetrics()
    var pageSize by remember { mutableStateOf(6) }
    var state by remember(items.size) { mutableStateOf(MinUiListState(size = items.size, pageSize = pageSize)) }
    LaunchedEffect(pageSize) { state = state.resized(items.size, pageSize) }
    val pick: (Int) -> Unit = { index -> items.getOrNull(index)?.second?.invoke() }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(onBack = onDismiss)
    MinUiScrim(
        modifier = Modifier
            .focusRequester(focus)
            .minUiKeys(
                onUp = { state = state.moveBy(-1) },
                onDown = { state = state.moveBy(1) },
                onPageUp = { state = state.pageBy(-1) },
                onPageDown = { state = state.pageBy(1) },
                onA = { pick(state.selected) },
                onB = onDismiss,
                onStart = onDismiss,
            )
            .focusable(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            MinUiTopBar(title = title)
            MinUiList(
                items = items,
                state = state,
                label = { it.first },
                onSelectedChange = { state = state.copy(selected = it) },
                onActivate = pick,
                onPageSize = { pageSize = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            MinUiHintBar(
                left = listOf(MinUiHint("B", "BACK", onDismiss)),
                right = listOf(MinUiHint("A", "SELECT") { pick(state.selected) }),
                modifier = Modifier.height(m.rowHeight),
            )
        }
    }
}

/**
 * Blocking "working…" screen: swallows every key, tap and back press until removed.
 * @param progress null = message only; 0..1 = progress bar + percentage; < 0 = sweeping bar.
 */
@Composable
fun MinUiBusy(message: String, progress: Float? = null) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler {}
    MinUiScrim(
        modifier = Modifier
            .focusRequester(focus)
            .minUiKeys(onUp = {}, onDown = {}, onPageUp = {}, onPageDown = {}, onA = {}, onB = {}, onY = {}, onStart = {})
            .focusable(),
    ) {
        MinUiBusyContent(message, progress)
    }
}

/** Top bar plus a message centered on the whole screen, optional progress bar and percentage. */
@Composable
private fun MinUiBusyContent(message: String, progress: Float?) {
    Box(Modifier.fillMaxSize()) {
        MinUiBusyBody(message, progress)
        MinUiTopBar(modifier = Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun MinUiBusyBody(message: String, progress: Float?) {
    val m = rememberMinUiMetrics()
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = m.gutter),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            color = MinUiColors.Text,
            fontFamily = MinUiFont,
            fontSize = m.smallText,
            lineHeight = m.smallText * 1.3f,
            textAlign = TextAlign.Center,
        )
        if (progress != null) {
            Spacer(Modifier.height(m.gutter))
            MinUiProgressBar(progress)
            if (progress >= 0f) {
                Spacer(Modifier.height(m.gutter * 0.5f))
                Text(
                    text = "${(progress.coerceIn(0f, 1f) * 100).roundToInt()}%",
                    color = MinUiColors.Dim,
                    fontFamily = MinUiFont,
                    fontSize = m.smallText,
                )
            }
        }
    }
}

/**
 * MinUI progress bar: dark rounded track, white fill. [progress] in 0..1 fills (eased);
 * below 0 shows a segment sweeping back and forth (unknown duration).
 */
@Composable
fun MinUiProgressBar(progress: Float, modifier: Modifier = Modifier) {
    val m = rememberMinUiMetrics()
    val height = m.hintHeight * 0.3f
    val shape = RoundedCornerShape(50)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth(0.6f)
            .widthIn(max = 480.dp)
            .height(height)
            .clip(shape)
            .background(MinUiColors.HintPill, shape),
    ) {
        if (progress >= 0f) {
            val shown by animateFloatAsState(progress.coerceIn(0f, 1f), label = "minui-progress")
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(shown)
                    .background(MinUiColors.Text, shape),
            )
        } else {
            val segment = maxWidth * 0.3f
            val sweep by rememberInfiniteTransition(label = "minui-sweep").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing), RepeatMode.Reverse),
                label = "minui-sweep-x",
            )
            Box(
                Modifier
                    .offset(x = (maxWidth - segment) * sweep)
                    .width(segment)
                    .fillMaxHeight()
                    .background(MinUiColors.Text, shape),
            )
        }
    }
}

/**
 * Full-screen MinUI replacement for the app's MessageDialog (the prompts preLaunchApp and the
 * app root raise). Same parameters minus icon/HTML; buttons map to the pad via
 * [minUiDialogBindings]. Its own window keeps it above navigation and first in line for keys.
 */
@Composable
fun MinUiMessageDialog(
    visible: Boolean,
    onDismissRequest: (() -> Unit)? = null,
    onConfirmClick: (() -> Unit)? = null,
    onDismissClick: (() -> Unit)? = null,
    onActionClick: (() -> Unit)? = null,
    confirmBtnText: String = "Confirm",
    dismissBtnText: String = "Dismiss",
    actionBtnText: String? = null,
    title: String? = null,
    message: String? = null,
) {
    if (!visible) return
    val bindings = minUiDialogBindings(
        confirm = onConfirmClick?.let { MinUiDialogButton(confirmBtnText, it) },
        dismiss = onDismissClick?.let { MinUiDialogButton(dismissBtnText, it) },
        action = if (actionBtnText != null && onActionClick != null) MinUiDialogButton(actionBtnText, onActionClick) else null,
        dismissRequest = onDismissRequest,
    )
    MinUiDialogWindow(onDismissRequest = { bindings.back?.invoke() }, dismissOnBackPress = true) {
        val m = rememberMinUiMetrics()
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        val step = with(LocalDensity.current) { m.rowHeight.toPx() }
        val scrollBy: (Float) -> Unit = { delta -> scope.launch { scroll.animateScrollBy(delta) } }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        MinUiScrim(
            modifier = Modifier
                .focusRequester(focus)
                .minUiKeys(
                    onUp = { scrollBy(-step) },
                    onDown = { scrollBy(step) },
                    onPageUp = { scrollBy(-step * 4) },
                    onPageDown = { scrollBy(step * 4) },
                    onA = { bindings.a?.onClick?.invoke() },
                    onB = { bindings.back?.invoke() },
                    onX = { bindings.x?.onClick?.invoke() },
                    onY = { bindings.y?.onClick?.invoke() },
                    onStart = {},
                )
                .focusable(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                MinUiTopBar(title = title)
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val viewport = maxHeight
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(scroll)) {
                        // Centered while it fits; scrolls from the top once it doesn't.
                        MinUiMessage(message.orEmpty(), Modifier.fillMaxWidth().heightIn(min = viewport))
                    }
                }
                val left = buildList {
                    if (bindings.showBackHint) bindings.back?.let { add(MinUiHint("B", "BACK", it)) }
                    bindings.x?.let { add(MinUiHint("X", it.label.uppercase(), it.onClick)) }
                    bindings.y?.let { add(MinUiHint("Y", it.label.uppercase(), it.onClick)) }
                }
                val right = listOfNotNull(bindings.a?.let { MinUiHint("A", it.label.uppercase(), it.onClick) })
                MinUiHintBar(left = left, right = right, modifier = Modifier.height(m.rowHeight))
            }
        }
    }
}

/**
 * MinUI replacement for SteamAgreementDialog (SSA / per-game EULA before a launch). The page
 * WebView is never focusable, so the pad drives it: up/down scroll, L1/R1 page, A accept, B decline.
 */
@Composable
fun MinUiAgreementDialog(
    title: String,
    url: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    MinUiDialogWindow(onDismissRequest = onDecline, dismissOnBackPress = true) {
        val m = rememberMinUiMetrics()
        val step = with(LocalDensity.current) { m.rowHeight.toPx().roundToInt() }
        var webView by remember { mutableStateOf<WebView?>(null) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        MinUiScrim(
            modifier = Modifier
                .focusRequester(focus)
                .minUiKeys(
                    onUp = { webView?.scrollBy(0, -step) },
                    onDown = { webView?.scrollBy(0, step) },
                    onPageUp = { webView?.pageUp(false) },
                    onPageDown = { webView?.pageDown(false) },
                    onA = onAccept,
                    onB = onDecline,
                    onX = {},
                    onY = {},
                    onStart = {},
                )
                .focusable(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                MinUiTopBar(title = title)
                Text(
                    text = stringResource(R.string.steam_agreement_message),
                    color = MinUiColors.Text,
                    fontFamily = MinUiFont,
                    fontSize = m.smallText,
                    lineHeight = m.smallText * 1.3f,
                    modifier = Modifier.padding(horizontal = m.gutter).padding(bottom = m.gutter * 0.5f),
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = m.gutter)) {
                    if (url.isNotBlank()) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                                    isFocusable = false
                                    isFocusableInTouchMode = false
                                    settings.setNeedInitialFocus(false)
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                            view?.scrollTo(0, 0)
                                        }
                                    }
                                    loadUrl(url)
                                    webView = this
                                }
                            },
                            onRelease = {
                                webView = null
                                it.destroy()
                            },
                        )
                    }
                }
                MinUiHintBar(
                    left = listOf(MinUiHint("B", stringResource(R.string.steam_agreement_decline).uppercase(), onDecline)),
                    right = listOf(MinUiHint("A", stringResource(R.string.steam_agreement_accept).uppercase(), onAccept)),
                    modifier = Modifier.height(m.rowHeight),
                )
            }
        }
    }
}

/**
 * Full-screen MinUI replacement for the app's LoadingDialog (shown by preLaunchApp at the
 * app root, above navigation, so it needs its own window like the original).
 */
@Composable
fun MinUiLoadingDialog(visible: Boolean, progress: Float, message: String) {
    if (!visible) return
    MinUiDialogWindow(onDismissRequest = {}, dismissOnBackPress = false) {
        Box(Modifier.fillMaxSize().background(MinUiColors.Background)) {
            MinUiBusyContent(message, progress)
        }
    }
}
