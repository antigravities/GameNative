package app.gamenative.ui.screen.minimal

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import app.gamenative.utils.HomeScreenSwitcher
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.PrefManager
import app.gamenative.R
import `in`.dragonbra.javasteam.enums.EPersonaState
import app.gamenative.data.GameSource
import app.gamenative.data.MinimalSyncProgress
import app.gamenative.service.SteamService
import app.gamenative.ui.component.dialog.ContainerConfigDialog
import app.gamenative.ui.screen.library.appscreen.BaseAppScreen
import app.gamenative.ui.screen.library.appscreen.applyKnownConfig
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.SteamGameOps
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import com.winlator.container.ContainerData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** What's drawn over the list (at most one at a time). */
private val PERSONA_CHOICES = listOf(EPersonaState.Online, EPersonaState.Away, EPersonaState.Invisible)

private fun personaLabel(state: EPersonaState): String = when (state) {
    EPersonaState.Online -> "Online"
    EPersonaState.Away -> "Away"
    EPersonaState.Invisible -> "Invisible (appear offline)"
    EPersonaState.Offline -> "Offline"
    else -> state.name
}

private sealed interface Overlay {
    data class Confirm(val message: String, val confirmLabel: String, val onConfirm: () -> Unit) : Overlay
    data object Menu : Overlay
    data object Status : Overlay
    data object Apps : Overlay
    data class Busy(val message: String) : Overlay
    data class Config(val entry: MinimalEntry, val data: ContainerData) : Overlay

    /** Fetching + applying the known (community best) config; shows component install progress. */
    data class KnownConfig(val entry: MinimalEntry) : Overlay
}

private fun steamAppId(gameId: Int) = "${GameSource.STEAM.name}_$gameId"

/** Launchable apps (label → launch intent) other than GameNative, sorted by label. */
private fun launchableApps(context: Context): List<Pair<String, Intent>> {
    val pm = context.packageManager
    val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(query, 0)
        .asSequence()
        .filter { it.activityInfo.packageName != context.packageName }
        .map { info ->
            val label = info.loadLabel(pm).toString()
            label to Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(info.activityInfo.packageName, info.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        .distinctBy { it.second.component }
        .sortedBy { it.first.lowercase() }
        .toList()
}

/**
 * modernMinimal launcher: MinUI list of the collection's games with the selected game's
 * capsule. A / tap = context action, Y / long-press = container config, Start = menu,
 * B / back = exit. Every action is shared by its button, its hint pill and its gesture.
 */
@Composable
fun MinimalLibraryScreen(
    onChangeCollection: () -> Unit,
    onClickPlay: (appId: String, asContainer: Boolean) -> Unit,
    onExit: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit,
    viewModel: MinimalLibraryViewModel = hiltViewModel(),
) {
    val m = rememberMinUiMetrics()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val content by viewModel.state.collectAsStateWithLifecycle()
    val sync by MinimalSyncProgress.progress.collectAsStateWithLifecycle()
    val entries = content.entries

    // Selection is tracked by appId so it survives list updates and returning from a game.
    var selectedAppId by rememberSaveable { mutableStateOf<Int?>(null) }
    var pageSize by remember { mutableStateOf(6) }
    var listState by remember { mutableStateOf(MinUiListState(pageSize = pageSize)) }
    LaunchedEffect(entries, pageSize) {
        val index = entries.indexOfFirst { it.appId == selectedAppId }
        listState = MinUiListState(
            selected = if (index >= 0) index else listState.selected,
            size = entries.size,
            pageSize = pageSize,
        ).resized(entries.size, pageSize)
        selectedAppId = entries.getOrNull(listState.selected)?.appId
    }
    fun update(newState: MinUiListState) {
        listState = newState
        selectedAppId = entries.getOrNull(newState.selected)?.appId
    }
    val selectedEntry: MinimalEntry? = entries.getOrNull(listState.selected)

    var overlay by remember { mutableStateOf<Overlay?>(null) }

    // --- Actions ---------------------------------------------------------------------------

    /** Show a blocking message while [block] runs, then return to the list. */
    fun runBusy(message: String, block: suspend () -> Unit) {
        overlay = Overlay.Busy(message)
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Minimal: '$message' failed")
                SnackbarManager.show(e.message ?: "Something went wrong")
            } finally {
                overlay = null
                viewModel.refreshState()
            }
        }
    }

    fun confirmUninstall(entry: MinimalEntry) {
        overlay = Overlay.Confirm("Uninstall ${entry.name}?", "UNINSTALL") {
            runBusy("Uninstalling…") {
                val ok = SteamGameOps.uninstall(context, entry.appId)
                SnackbarManager.show(
                    if (ok) context.getString(R.string.steam_uninstall_success, entry.name)
                    else context.getString(R.string.steam_uninstall_failed),
                )
            }
        }
    }

    fun confirmCancelDownload(entry: MinimalEntry) {
        overlay = Overlay.Confirm("Cancel the download of ${entry.name}?\nDownloaded files will be deleted.", "DELETE") {
            runBusy("Cancelling…") { SteamGameOps.cancelDownload(entry.appId) }
        }
    }

    /** Load the container settings and show the dialog (installing system files first if needed). */
    fun openConfig(entry: MinimalEntry) {
        val appId = steamAppId(entry.appId)
        suspend fun load() {
            val data = withContext(Dispatchers.IO) {
                ContainerUtils.toContainerData(ContainerUtils.getOrCreateContainer(context, appId))
            }
            overlay = Overlay.Config(entry, data)
        }
        if (SteamService.isImageFsInstalled(context)) {
            scope.launch { load() }
            return
        }
        overlay = Overlay.Confirm(
            "Install system files?\nThey're needed before ${entry.name} can be configured.",
            "INSTALL",
        ) {
            overlay = Overlay.Busy("Installing system files…")
            scope.launch {
                try {
                    SteamGameOps.installImageFs(context, appId)
                    SnackbarManager.show(context.getString(R.string.steam_imagefs_installed))
                    load()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SnackbarManager.show(context.getString(R.string.steam_imagefs_install_failed, e.message ?: ""))
                    overlay = null
                }
            }
        }
    }

    // Game whose known-config run may raise a "missing components" prompt.
    var knownConfigAppId by remember { mutableStateOf<String?>(null) }

    /** Same flow as the game page's "Use known config", behind a MinUI progress screen. */
    fun useKnownConfig(entry: MinimalEntry) {
        val appId = steamAppId(entry.appId)
        knownConfigAppId = appId
        overlay = Overlay.KnownConfig(entry)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { applyKnownConfig(context, appId, entry.appId, GameSource.STEAM) }
            } finally {
                overlay = null
            }
        }
    }

    val onA: () -> Unit = {
        selectedEntry?.let { e ->
            when (primaryAction(e)) {
                PrimaryAction.Launch -> onClickPlay(steamAppId(e.appId), false)
                // install/resume are debounced in the ViewModel (ignored while starting/running).
                PrimaryAction.Install -> viewModel.install(e.appId)
                PrimaryAction.Pause -> viewModel.pause(e.appId)
                PrimaryAction.Resume -> viewModel.resume(e.appId)
                PrimaryAction.Uninstall -> confirmUninstall(e)
                PrimaryAction.Starting -> Unit // already on its way

            }
        }
    }
    val onY: () -> Unit = { selectedEntry?.let(::openConfig) }
    val onStart: () -> Unit = { overlay = Overlay.Menu }

    // A known-config run that hit unavailable components asks "apply anyway?" (shared state
    // with the game page, which shows its own dialog for the same request).
    val missingComponents = knownConfigAppId?.let { BaseAppScreen.getMissingComponentsState(it) }
    val anyOverlay = overlay != null || missingComponents != null

    // B / system back exits (with the app's usual "press again" warning) when nothing is open.
    // As the home app there is nothing to exit to (Android would just relaunch us), so B is a
    // no-op there. Checked at press time; resolving the home activity is cheap.
    val exitOrStay: () -> Unit = { if (!HomeScreenSwitcher.isDefaultHome(context)) onExit() }
    BackHandler(enabled = !anyOverlay, onBack = exitOrStay)

    // Home key (home-screen mode) closes any open overlay.
    DisposableEffect(Unit) {
        val onHome: (AndroidEvent.HomePressed) -> Unit = { overlay = null }
        PluviaApp.events.on<AndroidEvent.HomePressed, Unit>(onHome)
        onDispose { PluviaApp.events.off<AndroidEvent.HomePressed, Unit>(onHome) }
    }

    // Re-take focus whenever an overlay closes so the controller drives the list again.
    val focus = remember { FocusRequester() }
    LaunchedEffect(anyOverlay) { if (!anyOverlay) focus.requestFocus() }

    // --- Layout ----------------------------------------------------------------------------

    Box(modifier = Modifier.fillMaxSize()) {
        MinUiScreen(
            modifier = Modifier
                .focusRequester(focus)
                .minUiKeys(
                    onUp = { update(listState.moveBy(-1)) },
                    onDown = { update(listState.moveBy(1)) },
                    onPageUp = { update(listState.pageBy(-1)) },
                    onPageDown = { update(listState.pageBy(1)) },
                    onA = onA,
                    onB = exitOrStay,
                    onY = onY,
                    onStart = onStart,
                )
                .focusable(),
            topBar = {
                val status = syncStatusShort(sync, content.pendingCount)
                if (status != null && !content.isSyncing) {
                    Text(
                        text = status,
                        color = MinUiColors.Dim,
                        fontFamily = MinUiFont,
                        fontSize = m.smallText,
                        maxLines = 1,
                    )
                }
            },
            hints = {
                MinUiHintBar(
                    left = listOf(MinUiHint("START", "MENU", onStart)),
                    right = if (selectedEntry == null) {
                        emptyList()
                    } else {
                        listOf(MinUiHint("Y", "CONFIG", onY), MinUiHint("A", primaryAction(selectedEntry).label, onA))
                    },
                    modifier = Modifier.fillMaxHeight(),
                )
            },
        ) {
            @Composable
            fun GameList(modifier: Modifier, maxPillWidth: Dp) = MinUiList(
                items = entries,
                state = listState,
                label = MinimalEntry::name,
                trailing = { e ->
                    when {
                        e.starting -> if (e.paused) "RESUMING…" else "INSTALLING…"
                        e.downloadPercent != null -> "${e.downloadPercent}%"
                        e.paused -> "PAUSED"
                        e.orphan -> "REMOVED"
                        else -> null
                    }
                },
                dimmed = { e -> e.orphan || (!e.installed && e.downloadPercent == null && !e.starting) },
                onSelectedChange = { update(listState.copy(selected = it)) },
                // The tapped row is already selected here, so the shared action applies to it.
                onActivate = { onA() },
                onLongPress = { index -> entries.getOrNull(index)?.let(::openConfig) },
                maxPillWidth = maxPillWidth,
                onPageSize = { pageSize = it },
                modifier = modifier,
            )

            when {
                content.isSyncing -> MinUiMessage(syncStatusLong(sync), Modifier.fillMaxSize())
                entries.isEmpty() -> MinUiMessage("This collection is empty", Modifier.fillMaxSize())
                m.isPortrait -> Column(modifier = Modifier.fillMaxSize()) {
                    // Portrait / phones: capsule centered in the top part, full-width list below.
                    MinimalArt(
                        appId = selectedAppId,
                        viewModel = viewModel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.4f)
                            .padding(horizontal = m.gutter, vertical = m.gutter * 0.3f),
                    )
                    GameList(Modifier.weight(0.6f).fillMaxWidth(), Dp.Unspecified)
                }
                else -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    // Landscape / handhelds: MinUI list on the left, portrait capsule on the right.
                    val listWidth = maxWidth * 0.58f
                    Row(modifier = Modifier.fillMaxSize()) {
                        GameList(Modifier.weight(0.58f).fillMaxHeight(), listWidth)
                        MinimalArt(
                            appId = selectedAppId,
                            viewModel = viewModel,
                            modifier = Modifier
                                .weight(0.42f)
                                .fillMaxHeight()
                                .padding(end = m.gutter, bottom = m.gutter * 0.3f),
                        )
                    }
                }
            }
        }

        // Overlays are siblings of the list (not children) so they get the keys first.
        when (val o = overlay) {
            null -> Unit
            is Overlay.Confirm -> MinUiConfirm(
                message = o.message,
                confirmLabel = o.confirmLabel,
                onConfirm = o.onConfirm,
                onDismiss = { overlay = null },
            )
            Overlay.Menu -> MinUiMenu(
                title = selectedEntry?.name ?: "Menu",
                items = buildList {
                    add("Change collection" to {
                        overlay = null
                        onChangeCollection()
                    })
                    selectedEntry?.let { e ->
                        if (!e.orphan) add("Use known config" to { useKnownConfig(e) })
                        if (e.downloadPercent != null || e.paused || e.starting) add("Cancel download" to { confirmCancelDownload(e) })
                        if (e.installed) add("Uninstall" to { confirmUninstall(e) })
                    }
                    add("Steam status: ${personaLabel(PrefManager.personaState)}" to {
                        overlay = Overlay.Status
                    })
                    add("Settings" to {
                        overlay = null
                        onOpenSettings()
                    })
                    // Ways out to the rest of Android (needed once this is the home screen).
                    add("Apps" to { overlay = Overlay.Apps })
                    add("Android settings" to {
                        overlay = null
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    })
                    if (HomeScreenSwitcher.isSupported) {
                        if (HomeScreenSwitcher.isEnabled(context)) {
                            add("Stop using as home screen" to {
                                overlay = null
                                HomeScreenSwitcher.setEnabled(context, false)
                            })
                        } else {
                            add("Use as home screen" to {
                                overlay = Overlay.Confirm(
                                    "Use GameNative as the home screen?\n" +
                                        "Pick it as the Home app on the next screen. Start → Apps reaches other apps; " +
                                        "\"Stop using as home screen\" undoes it.",
                                    "CONTINUE",
                                ) {
                                    overlay = null
                                    HomeScreenSwitcher.setEnabled(context, true)
                                }
                            })
                        }
                    }
                    add("Log out" to {
                        overlay = Overlay.Confirm("Log out of Steam?", "LOG OUT") {
                            overlay = null
                            onLogout()
                        }
                    })
                },
                onDismiss = { overlay = null },
            )
            // Same choices as the regular build's status picker; Invisible = appear offline.
            Overlay.Status -> MinUiMenu(
                title = "Steam status",
                items = PERSONA_CHOICES.map { state ->
                    personaLabel(state) to {
                        overlay = null
                        scope.launch { SteamService.setPersonaState(state) }
                        Unit
                    }
                },
                onDismiss = { overlay = Overlay.Menu },
            )
            Overlay.Apps -> {
                // Launchable apps other than GameNative, loaded off the main thread.
                val apps by produceState<List<Pair<String, Intent>>?>(initialValue = null) {
                    value = withContext(Dispatchers.IO) { launchableApps(context) }
                }
                val list = apps
                if (list == null) {
                    MinUiBusy("Loading apps…")
                } else {
                    MinUiMenu(
                        title = "Apps",
                        items = list.map { (label, launch) ->
                            label to {
                                overlay = null
                                runCatching { context.startActivity(launch) }
                                    .onFailure { Timber.w(it, "Minimal: could not open $label") }
                                Unit
                            }
                        },
                        onDismiss = { overlay = Overlay.Menu },
                    )
                }
            }
            is Overlay.Busy -> MinUiBusy(o.message)
            is Overlay.KnownConfig -> {
                // installMissingComponentsForConfig publishes progress here (observable state).
                val install = BaseAppScreen.getKnownConfigInstallState(o.entry.appId)
                if (install?.visible == true) {
                    MinUiBusy("Installing ${install.label}…", progress = install.progress)
                } else {
                    MinUiBusy("Applying known config for ${o.entry.name}…", progress = -1f)
                }
            }
            is Overlay.Config -> ContainerConfigDialog(
                visible = true,
                title = o.entry.name,
                initialConfig = o.data,
                onDismissRequest = { overlay = null },
                onSave = { config ->
                    scope.launch {
                        withContext(Dispatchers.IO) { SteamGameOps.saveContainerConfig(context, o.entry.appId, config) }
                        overlay = null
                    }
                },
            )
        }

        // Known config needs components that aren't available: offer to apply with defaults.
        val missingAppId = knownConfigAppId
        if (overlay == null && missingComponents != null && missingAppId != null) {
            MinUiConfirm(
                message = "This config needs components that aren't available:\n" +
                    missingComponents.components.joinToString() +
                    "\n\nApply it with defaults for those instead?",
                confirmLabel = "APPLY",
                onConfirm = {
                    BaseAppScreen.hideMissingComponentsDialog(missingAppId)
                    missingComponents.onApplyAnyway?.invoke()
                },
                onDismiss = { BaseAppScreen.hideMissingComponentsDialog(missingAppId) },
            )
        }
    }
}

/** Full-screen syncing message with package / game counts when known. */
internal fun syncStatusLong(p: MinimalSyncProgress.Progress): String = buildString {
    append("Syncing games…")
    if (p.packagesTotal > 0) append("\nPackages %,d / %,d".format(p.packagesShown, p.packagesTotal))
    if (p.appsTotal > 0) append("\nGames %,d / %,d".format(p.appsShown, p.appsTotal))
}

/** One-line status for the top bar while the list is already showing games. */
internal fun syncStatusShort(p: MinimalSyncProgress.Progress, pendingCount: Int): String? = when {
    p.packagesActive -> "Packages ${p.packagesShown * 100 / p.packagesTotal}%"
    p.appsActive -> "Games %,d / %,d".format(p.appsShown, p.appsTotal)
    pendingCount > 0 -> "Syncing $pendingCount…"
    else -> null
}

@Composable
private fun MinimalArt(
    appId: Int?,
    viewModel: MinimalLibraryViewModel,
    modifier: Modifier = Modifier,
) {
    val url by produceState<String?>(initialValue = null, appId) {
        value = appId?.let { viewModel.capsuleUrl(it) }
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Crossfade(targetState = url, label = "capsule") { u ->
            if (u != null) {
                CoilImage(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12)),
                    imageModel = { u },
                    imageOptions = ImageOptions(
                        contentScale = ContentScale.Fit,
                        contentDescription = null,
                    ),
                    loading = {},
                    failure = {},
                )
            } else {
                Box(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
