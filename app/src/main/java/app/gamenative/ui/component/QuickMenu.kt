package app.gamenative.ui.component

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.SteamGuide
import app.gamenative.service.SteamGuidesFetcher
import app.gamenative.ui.data.PerformanceHudConfig
import app.gamenative.ui.data.PerformanceHudSize
import app.gamenative.ui.enums.GuideCategory
import app.gamenative.ui.enums.GuideSort
import app.gamenative.translation.ScreenTranslator
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.adaptivePanelWidth
import app.gamenative.ui.util.rememberScreenWidthDp
import app.gamenative.utils.GameSessionTimer
import app.gamenative.utils.LocaleHelper
import app.gamenative.utils.MathUtils.normalizedProgress
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import com.winlator.container.Container
import com.winlator.renderer.GLRenderer
import com.winlator.renderer.VulkanRenderer
import com.winlator.winhandler.ProcessInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

object QuickMenuAction {
    const val KEYBOARD = 1
    const val INPUT_CONTROLS = 2
    const val EXIT_GAME = 3
    const val EDIT_CONTROLS = 4
    const val EDIT_PHYSICAL_CONTROLLER = 5
    const val PERFORMANCE_HUD = 6
    const val TOUCHSCREEN_MODE = 7
    const val DISABLE_MOUSE = 8
}

private object QuickMenuTab {
    const val HUD = 0
    const val LSFG = 1
    const val EFFECTS = 2
    const val CONTROLLER = 3
    const val TOOLS = 4
    const val GUIDES = 5
    const val TRANSLATE = 6
}

data class QuickMenuItem(
    val id: Int,
    val icon: ImageVector,
    val labelResId: Int,
    val accentColor: Color = Color.Unspecified,
    val enabled: Boolean = true,
)

/** Immutable screen-translation inputs for the Quick Menu, bundled to keep QuickMenu's param count low. */
data class QuickMenuTranslationState(
    val enabled: Boolean = false,
    val sourceLang: String = "ja",
    val targetLang: String = "en",
    val intervalMs: Int = 2000,
    val opacity: Float = 0.75f,
    // Max OCR input width (px); 0 = native. Higher = more accurate but slower.
    val ocrMaxWidth: Int = 1920,
    val status: ScreenTranslator.Status = ScreenTranslator.Status.Idle,
    val statusDetail: String? = null,
)

/** Screen-translation callbacks for the Quick Menu. @Stable so a remembered instance skips recomposition. */
@Stable
class QuickMenuTranslationCallbacks(
    val onEnabledChange: (Boolean) -> Unit = {},
    val onSourceLangChange: (String) -> Unit = {},
    val onTargetLangChange: (String) -> Unit = {},
    val onIntervalChange: (Int) -> Unit = {},
    val onOpacityChange: (Float) -> Unit = {},
    val onOcrMaxWidthChange: (Int) -> Unit = {},
)

private enum class PerformanceHudPreset(val labelResId: Int) {
    FPS_ONLY(R.string.performance_hud_preset_fps_only),
    ESSENTIAL(R.string.performance_hud_preset_essential),
    BATTERY(R.string.performance_hud_preset_battery),
    FULL(R.string.performance_hud_preset_full),
}

private fun applyPerformanceHudPreset(
    currentConfig: PerformanceHudConfig,
    preset: PerformanceHudPreset,
): PerformanceHudConfig {
    return when (preset) {
        PerformanceHudPreset.FPS_ONLY -> currentConfig.copy(
            showFrameRate = true,
            showCpuUsage = false,
            showGpuUsage = false,
            showRamUsage = false,
            showBatteryLevel = false,
            showPowerDraw = false,
            showBatteryRuntime = false,
            showBatteryTemperature = false,
            showClockTime = false,
            showCpuTemperature = false,
            showGpuTemperature = false,
            showFrameRateGraph = false,
            showCpuUsageGraph = false,
            showGpuUsageGraph = false,
        )

        PerformanceHudPreset.ESSENTIAL -> currentConfig.copy(
            showFrameRate = true,
            showCpuUsage = true,
            showGpuUsage = true,
            showRamUsage = true,
            showBatteryLevel = false,
            showPowerDraw = false,
            showBatteryRuntime = false,
            showBatteryTemperature = false,
            showClockTime = false,
            showCpuTemperature = false,
            showGpuTemperature = false,
            showFrameRateGraph = false,
            showCpuUsageGraph = false,
            showGpuUsageGraph = false,
        )

        PerformanceHudPreset.BATTERY -> currentConfig.copy(
            showFrameRate = true,
            showCpuUsage = true,
            showGpuUsage = true,
            showRamUsage = true,
            showBatteryLevel = true,
            showPowerDraw = false,
            showBatteryRuntime = true,
            showBatteryTemperature = true,
            showClockTime = false,
            showCpuTemperature = false,
            showGpuTemperature = false,
            showFrameRateGraph = true,
            showCpuUsageGraph = false,
            showGpuUsageGraph = false,
        )

        PerformanceHudPreset.FULL -> currentConfig.copy(
            showFrameRate = true,
            showCpuUsage = true,
            showGpuUsage = true,
            showRamUsage = true,
            showBatteryLevel = true,
            showPowerDraw = true,
            showBatteryRuntime = true,
            showBatteryTemperature = true,
            showClockTime = true,
            showCpuTemperature = true,
            showGpuTemperature = true,
            showFrameRateGraph = true,
            showCpuUsageGraph = true,
            showGpuUsageGraph = true,
        )
    }
}

private fun matchesPerformanceHudPreset(
    currentConfig: PerformanceHudConfig,
    preset: PerformanceHudPreset,
): Boolean {
    val presetConfig = applyPerformanceHudPreset(currentConfig, preset)
    return currentConfig.showFrameRate == presetConfig.showFrameRate &&
        currentConfig.showCpuUsage == presetConfig.showCpuUsage &&
        currentConfig.showGpuUsage == presetConfig.showGpuUsage &&
        currentConfig.showRamUsage == presetConfig.showRamUsage &&
        currentConfig.showBatteryLevel == presetConfig.showBatteryLevel &&
        currentConfig.showPowerDraw == presetConfig.showPowerDraw &&
        currentConfig.showBatteryRuntime == presetConfig.showBatteryRuntime &&
        currentConfig.showBatteryTemperature == presetConfig.showBatteryTemperature &&
        currentConfig.showClockTime == presetConfig.showClockTime &&
        currentConfig.showCpuTemperature == presetConfig.showCpuTemperature &&
        currentConfig.showGpuTemperature == presetConfig.showGpuTemperature &&
        currentConfig.showFrameRateGraph == presetConfig.showFrameRateGraph &&
        currentConfig.showCpuUsageGraph == presetConfig.showCpuUsageGraph &&
        currentConfig.showGpuUsageGraph == presetConfig.showGpuUsageGraph
}

// fpsLimiterSteps / fpsLimiterCurrentIndex / fpsLimiterProgress /
// nextFpsLimiterValue / previousFpsLimiterValue live in FpsLimiterUtils.kt

@Composable
fun QuickMenu(
    isVisible: Boolean,
    gameLogoUrl: String = "",
    gameName: String = "",
    // Numeric Steam app id of the running game; used to query Steam Community guides.
    gameAppId: Int = 0,
    // Guides are Steam-only UGC, so the Guides tab is hidden for other stores.
    isSteamGame: Boolean = false,
    onDismiss: () -> Unit,
    onItemSelected: (Int) -> Boolean,
    renderer: VulkanRenderer? = null,
    glRenderer: GLRenderer? = null,
    container: Container? = null,
    wineProcesses: List<ProcessInfo> = emptyList(),
    isWineProcessesLoading: Boolean = false,
    onToolsVisibilityChanged: (Boolean) -> Unit = {},
    onEndWineProcess: (ProcessInfo) -> Unit = {},
    isPerformanceHudEnabled: Boolean = false,
    performanceHudConfig: PerformanceHudConfig = PerformanceHudConfig(),
    fpsLimiterEnabled: Boolean = true,
    fpsLimiterTarget: Int = 60,
    fpsLimiterMax: Int = 60,
    onPerformanceHudConfigChanged: (PerformanceHudConfig) -> Unit = {},
    onFpsLimiterEnabledChanged: (Boolean) -> Unit = {},
    onFpsLimiterChanged: (Int) -> Unit = {},
    hasPhysicalController: Boolean = false,
    isTouchscreenModeActive: Boolean = false,
    onTouchGestureSettingsClick: () -> Unit = {},
    activeToggleIds: Set<Int> = emptySet(),
    // LSFG hot-reload state (tab only visible when isLsfgAvailable)
    isLsfgAvailable: Boolean = false,
    lsfgMultiplier: Int = 2,
    lsfgFlowScale: Float = 80f,
    lsfgPerformanceMode: Boolean = true,
    onLsfgMultiplierChanged: (Int) -> Unit = {},
    onLsfgFlowScaleChanged: (Float) -> Unit = {},
    onLsfgPerformanceModeChanged: (Boolean) -> Unit = {},
    // Screen translation (ML Kit OCR + Translate). Bundled into two holders to keep QuickMenu's total
    // parameter count down — a very large @Composable param list trips a Compose default-argument
    // codegen bug that corrupts unrelated params (e.g. gameName) on recomposition.
    translation: QuickMenuTranslationState = QuickMenuTranslationState(),
    translationCallbacks: QuickMenuTranslationCallbacks = QuickMenuTranslationCallbacks(),
    onAnimationComplete: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val exitGameItem = QuickMenuItem(
        id = QuickMenuAction.EXIT_GAME,
        icon = Icons.AutoMirrored.Filled.ExitToApp,
        labelResId = R.string.exit_game,
        accentColor = PluviaTheme.colors.accentDanger,
    )

    val controllerItems = buildList {
        add(
            QuickMenuItem(
                id = QuickMenuAction.DISABLE_MOUSE,
                icon = Icons.Filled.Mouse,
                labelResId = R.string.disable_mouse_input,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        add(
            QuickMenuItem(
                id = QuickMenuAction.KEYBOARD,
                icon = Icons.Default.Keyboard,
                labelResId = R.string.keyboard,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        add(
            QuickMenuItem(
                id = QuickMenuAction.INPUT_CONTROLS,
                icon = Icons.Default.TouchApp,
                labelResId = R.string.input_controls,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        if (hasPhysicalController) {
            add(
                QuickMenuItem(
                    id = QuickMenuAction.EDIT_PHYSICAL_CONTROLLER,
                    icon = Icons.Default.Gamepad,
                    labelResId = R.string.edit_physical_controller,
                    accentColor = PluviaTheme.colors.accentPurple,
                )
            )
        }
        add(
            QuickMenuItem(
                id = QuickMenuAction.EDIT_CONTROLS,
                icon = Icons.Default.Edit,
                labelResId = R.string.edit_controls,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        add(
            QuickMenuItem(
                id = QuickMenuAction.TOUCHSCREEN_MODE,
                icon = Icons.Default.Fingerprint,
                labelResId = R.string.touchscreen_mode,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
    }

    var selectedTab by rememberSaveable {
        mutableIntStateOf(
            // Fall back to HUD if the last-used tab isn't available for this game
            // (LSFG hidden when unsupported, Guides hidden for non-Steam games).
            when {
                PrefManager.quickMenuLastTab == QuickMenuTab.LSFG && !isLsfgAvailable -> QuickMenuTab.HUD
                PrefManager.quickMenuLastTab == QuickMenuTab.GUIDES && !isSteamGame -> QuickMenuTab.HUD
                else -> PrefManager.quickMenuLastTab
            }
        )
    }
    val selectedTabLabelResId = when (selectedTab) {
        QuickMenuTab.HUD -> R.string.performance_hud
        QuickMenuTab.LSFG -> R.string.lsfg_tab_title
        QuickMenuTab.EFFECTS -> R.string.screen_effects
        QuickMenuTab.TOOLS -> R.string.task_manager
        QuickMenuTab.GUIDES -> R.string.guides_tab_title
        QuickMenuTab.TRANSLATE -> R.string.translate_tab_title
        else -> R.string.quick_menu_tab_controller
    }

    // Embedded guide viewer: when the menu panel is no wider than half the screen
    // there's room to render the selected guide in a WebView beside the panel
    // (instead of kicking the user out to the browser). On narrower screens
    // (panel > half screen) selecting a guide keeps opening the system browser.
    val screenWidthDp = rememberScreenWidthDp()
    val panelWidth = adaptivePanelWidth(400.dp)
    val canShowGuideWebView = panelWidth.value <= screenWidthDp * 0.66f
    // The guide currently shown in the embedded WebView (null = viewer closed).
    var selectedGuide by remember { mutableStateOf<SteamGuide?>(null) }

    // Drop the open guide when leaving the Guides tab or closing the menu, so
    // re-opening the menu doesn't pop a stale WebView.
    LaunchedEffect(selectedTab, isVisible) {
        if (selectedTab != QuickMenuTab.GUIDES || !isVisible) selectedGuide = null
    }

    val hudScrollState = rememberScrollState()
    val effectsScrollState = rememberScrollState()
    val lsfgScrollState = rememberScrollState()
    val effectsTabFocusRequester = remember { FocusRequester() }
    val controllerScrollState = rememberScrollState()
    val lsfgTabFocusRequester = remember { FocusRequester() }
    val hudTabFocusRequester = remember { FocusRequester() }
    val controllerTabFocusRequester = remember { FocusRequester() }
    val toolsTabFocusRequester = remember { FocusRequester() }
    val hudItemFocusRequester = remember { FocusRequester() }
    val effectsItemFocusRequester = remember { FocusRequester() }
    val controllerItemFocusRequester = remember { FocusRequester() }
    val toolsItemFocusRequester = remember { FocusRequester() }
    val lsfgItemFocusRequester = remember { FocusRequester() }
    val guidesTabFocusRequester = remember { FocusRequester() }
    val translateTabFocusRequester = remember { FocusRequester() }
    val translateScrollState = rememberScrollState()

    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = isVisible

    LaunchedEffect(visibleState.currentState, visibleState.isIdle) {
        if (visibleState.isIdle) {
            onAnimationComplete(visibleState.currentState)
        }
    }

    BackHandler(enabled = isVisible) {
        onDismiss()
    }

    // Darkening veil behind the menu, except on the Screen Effects tab (which previews
    // CRT/FSR/FXAA on the live game frame, so the game must stay visible). Animated so
    // switching to/from Effects fades the veil smoothly.
    val veilAlpha by animateFloatAsState(
        targetValue = if (selectedTab == QuickMenuTab.EFFECTS) 0f else 0.5f,
        label = "menuVeil",
    )

    Box(modifier = modifier.fillMaxSize()) {
        // Shared backdrop: veil + game logo + play-time counter behind the panel, plus
        // tap-to-dismiss. Suppressed (veil fades to 0, info hidden) on the Screen Effects tab
        // so the live game frame stays visible while previewing effects.
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(150)),
        ) {
            SuspendBackdrop(
                logoUrl = gameLogoUrl,
                gameName = gameName,
                veilAlpha = veilAlpha,
                showInfo = selectedTab != QuickMenuTab.EFFECTS,
                onClick = onDismiss,
            )
        }

        AnimatedVisibility(
            visibleState = visibleState,
            enter = slideInHorizontally(
                initialOffsetX = { fullWidth -> -fullWidth },
                animationSpec = tween(200),
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { fullWidth -> -fullWidth },
                animationSpec = tween(150),
            ),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Surface(
                modifier = Modifier
                    .width(panelWidth)
                    .fillMaxHeight(),
                shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 24.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.quick_menu_title),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        QuickMenuCloseButton(onClick = onDismiss)
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .width(64.dp)
                                .fillMaxHeight()
                                .focusGroup(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val tabScrollState = rememberScrollState()
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(tabScrollState),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                QuickMenuTabButton(
                                    icon = Icons.Default.QueryStats,
                                    contentDescriptionResId = R.string.performance_hud,
                                    selected = selectedTab == QuickMenuTab.HUD,
                                    accentColor = PluviaTheme.colors.accentPurple,
                                    onSelected = {
                                        selectedTab = QuickMenuTab.HUD
                                        PrefManager.quickMenuLastTab = selectedTab
                                    },
                                    modifier = Modifier.width(56.dp),
                                    focusRequester = hudTabFocusRequester,
                                )
                                if (isLsfgAvailable) {
                                    QuickMenuTabButton(
                                        icon = Icons.Default.Speed,
                                        contentDescriptionResId = R.string.lsfg_tab_title,
                                        selected = selectedTab == QuickMenuTab.LSFG,
                                        accentColor = PluviaTheme.colors.accentPurple,
                                        onSelected = {
                                            selectedTab = QuickMenuTab.LSFG
                                            PrefManager.quickMenuLastTab = selectedTab
                                        },
                                        modifier = Modifier.width(56.dp),
                                        focusRequester = lsfgTabFocusRequester,
                                    )
                                }
                                if (renderer != null || glRenderer != null) {
                                    QuickMenuTabButton(
                                        icon = Icons.Default.AutoFixHigh,
                                        contentDescriptionResId = R.string.screen_effects,
                                        selected = selectedTab == QuickMenuTab.EFFECTS,
                                        accentColor = PluviaTheme.colors.accentPurple,
                                        onSelected = {
                                            selectedTab = QuickMenuTab.EFFECTS
                                            PrefManager.quickMenuLastTab = selectedTab
                                        },
                                        modifier = Modifier.width(56.dp),
                                        focusRequester = effectsTabFocusRequester,
                                    )
                                }
                                QuickMenuTabButton(
                                    icon = Icons.Default.Gamepad,
                                    contentDescriptionResId = R.string.quick_menu_tab_controller,
                                    selected = selectedTab == QuickMenuTab.CONTROLLER,
                                    accentColor = PluviaTheme.colors.accentPurple,
                                    onSelected = {
                                        selectedTab = QuickMenuTab.CONTROLLER
                                        PrefManager.quickMenuLastTab = selectedTab
                                    },
                                    modifier = Modifier.width(56.dp),
                                    focusRequester = controllerTabFocusRequester,
                                )
                                QuickMenuTabButton(
                                    icon = Icons.Default.BarChart,
                                    contentDescriptionResId = R.string.task_manager,
                                    selected = selectedTab == QuickMenuTab.TOOLS,
                                    accentColor = PluviaTheme.colors.accentPurple,
                                    onSelected = { selectedTab = QuickMenuTab.TOOLS },
                                    modifier = Modifier.width(56.dp),
                                    focusRequester = toolsTabFocusRequester,
                                )
                                // Guides are Steam-only UGC, so only show for Steam games.
                                if (isSteamGame) {
                                    QuickMenuTabButton(
                                        icon = Icons.AutoMirrored.Filled.MenuBook,
                                        contentDescriptionResId = R.string.guides_tab_title,
                                        selected = selectedTab == QuickMenuTab.GUIDES,
                                        accentColor = PluviaTheme.colors.accentPurple,
                                        onSelected = {
                                            selectedTab = QuickMenuTab.GUIDES
                                            PrefManager.quickMenuLastTab = selectedTab
                                        },
                                        modifier = Modifier.width(56.dp),
                                        focusRequester = guidesTabFocusRequester,
                                    )
                                }
                                QuickMenuTabButton(
                                    icon = Icons.Default.Translate,
                                    contentDescriptionResId = R.string.translate_tab_title,
                                    selected = selectedTab == QuickMenuTab.TRANSLATE,
                                    accentColor = PluviaTheme.colors.accentPurple,
                                    onSelected = {
                                        selectedTab = QuickMenuTab.TRANSLATE
                                        PrefManager.quickMenuLastTab = selectedTab
                                    },
                                    modifier = Modifier.width(56.dp),
                                    focusRequester = translateTabFocusRequester,
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp, vertical = 12.dp)
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                            )

                            QuickMenuRailActionButton(
                                item = exitGameItem,
                                onClick = {
                                    if (onItemSelected(QuickMenuAction.EXIT_GAME)) {
                                        onDismiss()
                                    }
                                },
                                modifier = Modifier.width(56.dp),
                            )
                        }

                        Box(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxSize(),
                        ) {
                            Text(
                                text = stringResource(selectedTabLabelResId),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )

                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                            )

                            Box(
                                modifier = Modifier.weight(1f),
                            ) {
                                when (selectedTab) {
                                    QuickMenuTab.HUD -> {
                                        PerformanceHudQuickMenuTab(
                                            isPerformanceHudEnabled = isPerformanceHudEnabled,
                                            performanceHudConfig = performanceHudConfig,
                                            fpsLimiterEnabled = fpsLimiterEnabled,
                                            fpsLimiterTarget = fpsLimiterTarget,
                                            fpsLimiterMax = fpsLimiterMax,
                                            lsfgMultiplier = if (isLsfgAvailable) lsfgMultiplier else 0,
                                            onTogglePerformanceHud = {
                                                onItemSelected(QuickMenuAction.PERFORMANCE_HUD)
                                            },
                                            onPerformanceHudConfigChanged = onPerformanceHudConfigChanged,
                                            onFpsLimiterEnabledChanged = onFpsLimiterEnabledChanged,
                                            onFpsLimiterChanged = onFpsLimiterChanged,
                                            scrollState = hudScrollState,
                                            focusRequester = hudItemFocusRequester,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }

                                    QuickMenuTab.LSFG -> {
                                        LsfgQuickMenuTab(
                                            multiplier = lsfgMultiplier,
                                            flowScale = lsfgFlowScale,
                                            performanceMode = lsfgPerformanceMode,
                                            onMultiplierChanged = onLsfgMultiplierChanged,
                                            onFlowScaleChanged = onLsfgFlowScaleChanged,
                                            onPerformanceModeChanged = onLsfgPerformanceModeChanged,
                                            scrollState = lsfgScrollState,
                                            focusRequester = lsfgItemFocusRequester,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }

                                    QuickMenuTab.EFFECTS -> {
                                        if (renderer != null) {
                                            ScreenEffectsTabContent(
                                                renderer = renderer,
                                                container = container,
                                                modifier = Modifier.fillMaxSize(),
                                                firstItemFocusRequester = effectsItemFocusRequester,
                                                scrollState = effectsScrollState,
                                            )
                                        } else if (glRenderer != null) {
                                            GLScreenEffectsTabContent(
                                                renderer = glRenderer,
                                                container = container,
                                                modifier = Modifier.fillMaxSize(),
                                                firstItemFocusRequester = effectsItemFocusRequester,
                                                scrollState = effectsScrollState,
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(horizontal = 8.dp, vertical = 16.dp),
                                                contentAlignment = Alignment.TopStart,
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.main_loading),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }

                                    QuickMenuTab.TOOLS -> {
                                        ToolsQuickMenuTab(
                                            processes = wineProcesses,
                                            isLoadingProcesses = isWineProcessesLoading,
                                            onEndProcess = onEndWineProcess,
                                            onItemSelected = onItemSelected,
                                            onDismiss = onDismiss,
                                            firstItemFocusRequester = toolsItemFocusRequester,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }

                                    QuickMenuTab.GUIDES -> {
                                        GuidesQuickMenuTab(
                                            gameAppId = gameAppId,
                                            selectedGuideId = selectedGuide?.id,
                                            // Non-null only when there's room for the side-by-side
                                            // viewer; the tab falls back to the browser otherwise.
                                            onShowInWebView = if (canShowGuideWebView) {
                                                { guide ->
                                                    // Toggle: tapping the open guide again closes it.
                                                    selectedGuide = if (selectedGuide?.id == guide.id) null else guide
                                                }
                                            } else null,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }

                                    QuickMenuTab.TRANSLATE -> {
                                        TranslateQuickMenuTab(
                                            enabled = translation.enabled,
                                            sourceLang = translation.sourceLang,
                                            targetLang = translation.targetLang,
                                            intervalMs = translation.intervalMs,
                                            opacity = translation.opacity,
                                            ocrMaxWidth = translation.ocrMaxWidth,
                                            status = translation.status,
                                            statusDetail = translation.statusDetail,
                                            onEnabledChange = translationCallbacks.onEnabledChange,
                                            onSourceLangChange = translationCallbacks.onSourceLangChange,
                                            onTargetLangChange = translationCallbacks.onTargetLangChange,
                                            onIntervalChange = translationCallbacks.onIntervalChange,
                                            onOpacityChange = translationCallbacks.onOpacityChange,
                                            onOcrMaxWidthChange = translationCallbacks.onOcrMaxWidthChange,
                                            scrollState = translateScrollState,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }

                                    else -> {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .verticalScroll(controllerScrollState)
                                                .focusGroup(),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            controllerItems.forEachIndexed { index, item ->
                                                QuickMenuItemRow(
                                                    item = item,
                                                    isActive = item.id in activeToggleIds,
                                                    onClick = {
                                                        if (onItemSelected(item.id)) {
                                                            onDismiss()
                                                        }
                                                    },
                                                    focusRequester = if (index == 0) controllerItemFocusRequester else null,
                                                    secondaryIcon = if (item.id == QuickMenuAction.TOUCHSCREEN_MODE && isTouchscreenModeActive)
                                                        Icons.Default.Settings else null,
                                                    onSecondaryClick = if (item.id == QuickMenuAction.TOUCHSCREEN_MODE && isTouchscreenModeActive)
                                                        onTouchGestureSettingsClick else null,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Embedded guide viewer, rendered to the right of the menu panel. Only on
        // wide screens (canShowGuideWebView) and only while a guide is selected.
        val guide = selectedGuide
        AnimatedVisibility(
            visible = isVisible && canShowGuideWebView && guide != null,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(150)),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            if (guide != null) {
                // Registered after the menu's BackHandler (:412), so it takes
                // priority: Back closes the viewer first, then the menu.
                BackHandler(enabled = true) { selectedGuide = null }
                GuideWebViewPanel(
                    url = guide.url,
                    title = guide.title,
                    // Sit flush to the right of the menu panel.
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = panelWidth),
                    onClose = { selectedGuide = null },
                )
            }
        }
    }

    LaunchedEffect(isVisible, selectedTab) {
        onToolsVisibilityChanged(isVisible && selectedTab == QuickMenuTab.TOOLS)
    }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            repeat(3) {
                try {
                    when (selectedTab) {
                        QuickMenuTab.HUD -> hudItemFocusRequester.requestFocus()
                        QuickMenuTab.LSFG -> lsfgItemFocusRequester.requestFocus()
                        QuickMenuTab.EFFECTS -> effectsItemFocusRequester.requestFocus()
                        QuickMenuTab.TOOLS -> toolsItemFocusRequester.requestFocus()
                        // Guides intentionally has no auto-focus (see GuidesQuickMenuTab).
                        QuickMenuTab.GUIDES -> Unit
                        QuickMenuTab.TRANSLATE -> Unit
                        else -> controllerItemFocusRequester.requestFocus()
                    }
                    return@LaunchedEffect
                } catch (_: Exception) {
                    delay(80)
                }
            }
        }
    }
}

// Languages the user can pick. Source languages are limited to scripts we ship an OCR recognizer for
// (the Japanese/Chinese/Korean recognizers also read embedded Latin). Codes are ML Kit TranslateLanguage
// tags. Display names are proper nouns, so they stay in code rather than strings.xml.
private val TRANSLATE_SOURCE_LANGS = listOf(
    "ja" to "Japanese",
    "zh" to "Chinese",
    "ko" to "Korean",
    "en" to "English",
)
private val TRANSLATE_TARGET_LANGS = listOf(
    "en" to "English",
    "ja" to "Japanese",
    "zh" to "Chinese",
    "ko" to "Korean",
    "es" to "Spanish",
    "fr" to "French",
    "de" to "German",
    "ru" to "Russian",
    "pt" to "Portuguese",
    "it" to "Italian",
)

// OCR quality presets: max input width (px) → label. 0 = native resolution (no downscale).
private val TRANSLATE_QUALITY_PRESETS = listOf(
    1280 to R.string.translate_quality_fast,
    1920 to R.string.translate_quality_balanced,
    0 to R.string.translate_quality_accurate,
)

@Composable
private fun TranslateQuickMenuTab(
    enabled: Boolean,
    sourceLang: String,
    targetLang: String,
    intervalMs: Int,
    opacity: Float,
    ocrMaxWidth: Int,
    status: ScreenTranslator.Status,
    statusDetail: String?,
    onEnabledChange: (Boolean) -> Unit,
    onSourceLangChange: (String) -> Unit,
    onTargetLangChange: (String) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onOcrMaxWidthChange: (Int) -> Unit,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Live on/off
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.translate_live),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }

        // Status line. Error shows the underlying message when available; the OCR-module download is a
        // transient wait, not an error.
        val statusText = when (status) {
            ScreenTranslator.Status.DownloadingOcr -> stringResource(R.string.translate_status_downloading_ocr)
            ScreenTranslator.Status.DownloadingModel -> stringResource(R.string.translate_status_downloading)
            ScreenTranslator.Status.Working -> stringResource(R.string.translate_status_working)
            ScreenTranslator.Status.Ready -> stringResource(R.string.translate_status_ready)
            ScreenTranslator.Status.Error -> if (statusDetail != null) {
                stringResource(R.string.translate_status_error_detail, statusDetail)
            } else {
                stringResource(R.string.translate_status_error)
            }
            ScreenTranslator.Status.Idle -> stringResource(R.string.translate_status_idle)
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Source language
        Text(
            text = stringResource(R.string.translate_source),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        LanguageChips(options = TRANSLATE_SOURCE_LANGS, selected = sourceLang, onSelect = onSourceLangChange)

        // Target language
        Text(
            text = stringResource(R.string.translate_target),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        LanguageChips(options = TRANSLATE_TARGET_LANGS, selected = targetLang, onSelect = onTargetLangChange)

        // OCR quality: higher resolution = more accurate on small text, but slower passes.
        Text(
            text = stringResource(R.string.translate_quality),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TRANSLATE_QUALITY_PRESETS.forEach { (width, labelResId) ->
                FilterChip(
                    selected = width == ocrMaxWidth,
                    onClick = { onOcrMaxWidthChange(width) },
                    label = { Text(stringResource(labelResId)) },
                )
            }
        }

        // Update interval (seconds)
        Text(
            text = stringResource(R.string.translate_interval, intervalMs / 1000f),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Slider(
            value = intervalMs.toFloat(),
            onValueChange = { onIntervalChange(it.roundToInt()) },
            valueRange = 500f..5000f,
            steps = 8, // 500ms increments
        )

        // Overlay box opacity
        Text(
            text = stringResource(R.string.translate_opacity, (opacity * 100).roundToInt()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Slider(
            value = opacity,
            onValueChange = onOpacityChange,
            valueRange = 0.3f..1f,
        )
    }
}

@Composable
private fun LanguageChips(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (code, label) ->
            FilterChip(
                selected = code == selected,
                onClick = { onSelect(code) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun ToolsQuickMenuTab(
    processes: List<ProcessInfo>,
    isLoadingProcesses: Boolean,
    onEndProcess: (ProcessInfo) -> Unit,
    onItemSelected: ((Int) -> Boolean)? = null,
    onDismiss: (() -> Unit)? = null,
    firstItemFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val accentColor = PluviaTheme.colors.accentPurple

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        QuickMenuSectionHeader(
            title = if (isLoadingProcesses) {
                stringResource(R.string.main_loading)
            } else {
                stringResource(R.string.tools_wine_processes_running_hint, processes.size)
            },
        )

        if (!isLoadingProcesses && processes.isEmpty()) {
            Text(
                text = stringResource(R.string.tools_wine_processes_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        } else {
            processes.forEachIndexed { index, process ->
                QuickMenuProcessRow(
                    title = process.name + if (process.wow64Process) " *32" else "",
                    subtitle = process.formattedMemoryUsage,
                    accentColor = accentColor,
                    onEndProcess = {
                        onEndProcess(process)
                    },
                    // Focus is claimed by the screenshot row above when it is shown.
                    focusRequester = if (index == 0 && onItemSelected == null) firstItemFocusRequester else null,
                )
            }
        }
    }
}

/**
 * Guides tab: lists Steam Community guides for the running game. Sort + a single
 * category filter drive a re-query.
 *
 * When [onShowInWebView] is non-null (wide screens), tapping a guide hands it up
 * to the embedded WebView beside the menu; [selectedGuideId] is the one currently
 * open so it can be highlighted. When [onShowInWebView] is null (narrow screens),
 * tapping a guide opens it in the system browser instead.
 *
 * State is held locally (no ViewModel) to match the rest of the Quick Menu;
 * [SteamGuidesFetcher] caches results so flipping back to a prior sort/category
 * is instant.
 */
@Composable
private fun GuidesQuickMenuTab(
    gameAppId: Int,
    selectedGuideId: Long? = null,
    onShowInWebView: ((SteamGuide) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val accentColor = PluviaTheme.colors.accentPurple
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var selectedSort by rememberSaveable { mutableStateOf(GuideSort.MOST_POPULAR) }
    var selectedCategory by rememberSaveable { mutableStateOf(GuideCategory.ALL) }
    var languageOnly by rememberSaveable { mutableStateOf(true) }
    // Accumulated guides across loaded pages, plus the cursor for the next page
    // (null once Steam reports no more results).
    var guides by remember { mutableStateOf<List<SteamGuide>>(emptyList()) }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var isLoadingInitial by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }

    // Resolve the current language's Steam guide tag once. Null = no sensible tag,
    // in which case we don't offer the language toggle at all.
    val languageTag = remember { LocaleHelper.steamGuideLanguageTag() }
    val languageDisplayName = remember {
        // For an explicit app language use its label; for "System Default" (blank)
        // fall back to the device locale's own display name (e.g. "English").
        val code = PrefManager.appLanguage
        if (code.isBlank()) {
            java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.getDefault())
                .replaceFirstChar { it.uppercase() }
        } else {
            LocaleHelper.getLanguageDisplayName(code)
        }
    }
    // First page: reset and reload whenever the game, sort, category, or language
    // toggle changes. The await() runs on IO.
    LaunchedEffect(gameAppId, selectedSort, selectedCategory, languageOnly) {
        isLoadingInitial = true
        guides = emptyList()
        nextCursor = null
        val page = withContext(Dispatchers.IO) {
            SteamGuidesFetcher.queryGuides(
                appId = gameAppId,
                sort = selectedSort,
                category = selectedCategory,
                languageTag = if (languageOnly) languageTag else null,
                cursor = SteamGuidesFetcher.FIRST_CURSOR,
            )
        }
        guides = page.guides
        nextCursor = page.nextCursor
        isLoadingInitial = false
        listState.scrollToItem(0)
    }

    // Infinite scroll: when the last visible row nears the end, fetch the next page.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (lastIndex, total) ->
                val cursor = nextCursor
                if (cursor != null && !isLoadingInitial && !isLoadingMore &&
                    total > 0 && lastIndex >= total - 3
                ) {
                    isLoadingMore = true
                    val page = withContext(Dispatchers.IO) {
                        SteamGuidesFetcher.queryGuides(
                            appId = gameAppId,
                            sort = selectedSort,
                            category = selectedCategory,
                            languageTag = if (languageOnly) languageTag else null,
                            cursor = cursor,
                        )
                    }
                    guides = (guides + page.guides).distinctBy { it.id }
                    nextCursor = page.nextCursor
                    isLoadingMore = false
                }
            }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── Sort selector ────────────────────────────────────────────────
        item {
            QuickMenuSectionHeader(title = stringResource(R.string.guides_sort_header))
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GuideSort.entries.forEach { sort ->
                    QuickMenuChoiceChip(
                        text = stringResource(sort.displayTextRes),
                        selected = selectedSort == sort,
                        accentColor = accentColor,
                        onClick = { selectedSort = sort },
                        // No auto-focus anchor here: in touch mode a programmatically
                        // focused chip stays focused all session and renders heavier
                        // than a plain selected chip. Controller users still reach the
                        // chips via D-pad focus search.
                    )
                }
            }
        }

        // ── Category filter (single-select, like Steam's guide browser) ───
        item {
            Spacer(modifier = Modifier.height(8.dp))
            QuickMenuSectionHeader(title = stringResource(R.string.guides_category_header))
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GuideCategory.entries.forEach { category ->
                    QuickMenuChoiceChip(
                        text = stringResource(category.displayTextRes),
                        selected = selectedCategory == category,
                        accentColor = accentColor,
                        onClick = { selectedCategory = category },
                    )
                }
            }
        }

        // ── Language filter (only when we have a tag for the current language) ──
        if (languageTag != null) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                QuickMenuToggleRow(
                    title = stringResource(R.string.guides_language_only_title, languageDisplayName),
                    enabled = languageOnly,
                    onToggle = { languageOnly = !languageOnly },
                    accentColor = accentColor,
                )
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }

        // ── Results ──────────────────────────────────────────────────────
        when {
            isLoadingInitial -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = accentColor)
                    }
                }
            }

            guides.isEmpty() -> {
                item {
                    Text(
                        text = stringResource(R.string.guides_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            else -> {
                items(guides, key = { it.id }) { guide ->
                    GuideRow(
                        guide = guide,
                        accentColor = accentColor,
                        isSelected = guide.id == selectedGuideId,
                        onClick = {
                            val showInWebView = onShowInWebView
                            if (showInWebView != null) {
                                // Wide screen: open in the embedded viewer beside the menu.
                                showInWebView(guide)
                            } else {
                                // Narrow screen: opening the browser backgrounds the game;
                                // the existing suspend/veil handling covers that.
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(guide.url)))
                                }
                            }
                        },
                    )
                }
                // Footer spinner while the next page loads.
                if (nextCursor != null) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = accentColor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideRow(
    guide: SteamGuide,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    // Treat the guide currently open in the embedded viewer the same as focused,
    // so it stays visually highlighted in the list.
    val highlighted = isFocused || isSelected
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(shape)
            .background(
                if (highlighted) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.14f),
                            accentColor.copy(alpha = 0.06f),
                        ),
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f),
                        ),
                    )
                },
            )
            .then(
                if (highlighted) {
                    Modifier.border(width = 2.dp, color = accentColor.copy(alpha = 0.8f), shape = shape)
                } else {
                    Modifier
                }
            )
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && isFocused) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_BUTTON_A,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER -> {
                            onClick()
                            true
                        }

                        else -> false
                    }
                } else {
                    false
                }
            }
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (guide.previewUrl.isNotEmpty()) {
            CoilImage(
                modifier = Modifier
                    .size(width = 88.dp, height = 50.dp)
                    .clip(RoundedCornerShape(8.dp)),
                imageModel = { guide.previewUrl },
                imageOptions = ImageOptions(contentScale = ContentScale.Crop),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = guide.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (highlighted) accentColor else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (guide.ratingsCount > 0) {
                GuideStarRating(
                    score = guide.score,
                    ratingsCount = guide.ratingsCount,
                    accentColor = if (highlighted) accentColor else accentColor.copy(alpha = 0.85f),
                )
            } else {
                Text(
                    text = stringResource(R.string.guides_no_ratings),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (highlighted) accentColor.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Embedded guide viewer shown beside the Quick Menu on wide screens. A header
 * (guide title + close button) sits above a WebView that loads [url].
 *
 * The page itself is loaded programmatically (which does NOT trigger
 * [WebViewClient.shouldOverrideUrlLoading]), so any URL that reaches
 * shouldOverrideUrlLoading is a user-tapped link inside the guide — we hand those
 * to the system browser and keep them out of the embedded view.
 */
@Composable
private fun GuideWebViewPanel(
    url: String,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    // Track the last url we asked the WebView to load so switching guides without
    // closing the viewer triggers a reload (see the update lambda below).
    var loadedUrl by remember { mutableStateOf("") }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 24.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                QuickMenuCloseButton(onClick = onClose)
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            )

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = PluviaTheme.colors.accentPurple,
                )
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Spoof the Steam Deck client UA so Steam Community serves the
                        // leaner Deck-styled guide page (hides extra header/promo chrome).
                        settings.userAgentString =
                            "Mozilla/5.0 (X11; Linux x86_64; Valve Steam Client/Steam Deck [Steam Deck Stable]/default/0) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.183 Safari/537.36"
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                // User tapped a link inside the guide — open it externally.
                                val target = request?.url ?: return false
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, target))
                                }
                                return true
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                isLoading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                            }
                        }
                        loadUrl(url)
                        loadedUrl = url
                    }
                },
                update = { webView ->
                    // Selecting a different guide while the viewer is open reloads it.
                    if (loadedUrl != url) {
                        webView.loadUrl(url)
                        loadedUrl = url
                    }
                },
            )
        }
    }
}

/**
 * Renders a guide's rating as five stars derived from its 0–1 [score]
 * (Steam's own basis for the star rating), with the total [ratingsCount] beside.
 */
@Composable
private fun GuideStarRating(
    score: Float,
    ratingsCount: Int,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    val stars = (score * 5f).coerceIn(0f, 5f)
    val emptyColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (i in 1..5) {
            val icon = when {
                stars >= i -> Icons.Filled.Star
                stars >= i - 0.5f -> Icons.Filled.StarHalf
                else -> Icons.Filled.StarBorder
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (stars >= i - 0.5f) accentColor else emptyColor,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.guides_ratings_count, ratingsCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun PerformanceHudQuickMenuTab(
    isPerformanceHudEnabled: Boolean,
    performanceHudConfig: PerformanceHudConfig,
    fpsLimiterEnabled: Boolean,
    fpsLimiterTarget: Int,
    fpsLimiterMax: Int,
    lsfgMultiplier: Int,
    onTogglePerformanceHud: () -> Unit,
    onPerformanceHudConfigChanged: (PerformanceHudConfig) -> Unit,
    onFpsLimiterEnabledChanged: (Boolean) -> Unit,
    onFpsLimiterChanged: (Int) -> Unit,
    scrollState: ScrollState,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val accentColor = PluviaTheme.colors.accentPurple

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── FPS Limiter (topmost) ────────────────────────────────────────
        val limiterControlledByLsfg = lsfgMultiplier >= 2
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_fps_limiter),
            subtitle = if (limiterControlledByLsfg) {
                stringResource(R.string.performance_hud_fps_limiter_lsfg_override)
            } else null,
            enabled = fpsLimiterEnabled && !limiterControlledByLsfg,
            onToggle = {
                if (!limiterControlledByLsfg) onFpsLimiterEnabledChanged(!fpsLimiterEnabled)
            },
            accentColor = accentColor,
            focusRequester = focusRequester,
        )

        AnimatedVisibility(
            visible = fpsLimiterEnabled && !limiterControlledByLsfg,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                Spacer(modifier = Modifier.height(4.dp))
                QuickMenuAdjustmentRow(
                    title = stringResource(R.string.performance_hud_fps_limiter_target),
                    valueText = stringResource(
                        R.string.performance_hud_fps_limiter_value,
                        fpsLimiterTarget,
                    ),
                    progress = fpsLimiterProgress(fpsLimiterTarget, fpsLimiterMax),
                    onDecrease = {
                        onFpsLimiterChanged(previousFpsLimiterValue(fpsLimiterTarget, fpsLimiterMax))
                    },
                    onIncrease = {
                        onFpsLimiterChanged(nextFpsLimiterValue(fpsLimiterTarget, fpsLimiterMax))
                    },
                    accentColor = accentColor,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Performance HUD ──────────────────────────────────────────────
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud),
            subtitle = stringResource(R.string.performance_hud_description),
            enabled = isPerformanceHudEnabled,
            onToggle = onTogglePerformanceHud,
            accentColor = accentColor,
        )

        Spacer(modifier = Modifier.height(8.dp))

        QuickMenuSectionHeader(
            title = stringResource(R.string.performance_hud_presets),
        )

        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PerformanceHudPreset.values().forEach { preset ->
                QuickMenuChoiceChip(
                    text = stringResource(preset.labelResId),
                    selected = matchesPerformanceHudPreset(performanceHudConfig, preset),
                    accentColor = accentColor,
                    onClick = {
                        onPerformanceHudConfigChanged(applyPerformanceHudPreset(performanceHudConfig, preset))
                        if (!isPerformanceHudEnabled) {
                            onTogglePerformanceHud()
                        }
                    },
                    modifier = Modifier.width(56.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        QuickMenuSectionHeader(
            title = stringResource(R.string.performance_hud_appearance),
        )

        Text(
            text = stringResource(R.string.performance_hud_size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )

        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                PerformanceHudSize.SMALL to R.string.performance_hud_size_small,
                PerformanceHudSize.MEDIUM to R.string.performance_hud_size_medium,
                PerformanceHudSize.LARGE to R.string.performance_hud_size_large,
            ).forEach { (size, labelResId) ->
                QuickMenuChoiceChip(
                    text = stringResource(labelResId),
                    selected = performanceHudConfig.size == size,
                    accentColor = accentColor,
                    onClick = {
                        onPerformanceHudConfigChanged(performanceHudConfig.copy(size = size))
                    },
                    modifier = Modifier.width(56.dp),
                )
            }
        }

        QuickMenuAdjustmentRow(
            title = stringResource(R.string.performance_hud_background_opacity),
            valueText = stringResource(
                R.string.performance_hud_percentage_value,
                (performanceHudConfig.backgroundOpacity * 100f).roundToInt(),
            ),
            progress = normalizedProgress(performanceHudConfig.backgroundOpacity, 0f, 1f),
            onDecrease = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(
                        backgroundOpacity = (performanceHudConfig.backgroundOpacity - 0.05f).coerceIn(0f, 1f),
                    ),
                )
            },
            onIncrease = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(
                        backgroundOpacity = (performanceHudConfig.backgroundOpacity + 0.05f).coerceIn(0f, 1f),
                    ),
                )
            },
            accentColor = accentColor,
        )

        QuickMenuAdjustmentRow(
            title = stringResource(R.string.performance_hud_color_intensity),
            valueText = stringResource(
                R.string.performance_hud_percentage_value,
                (performanceHudConfig.colorIntensity * 100f).roundToInt(),
            ),
            progress = normalizedProgress(performanceHudConfig.colorIntensity, 0f, 1f),
            onDecrease = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(
                        colorIntensity = (performanceHudConfig.colorIntensity - 0.05f).coerceIn(0f, 1f),
                    ),
                )
            },
            onIncrease = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(
                        colorIntensity = (performanceHudConfig.colorIntensity + 0.05f).coerceIn(0f, 1f),
                    ),
                )
            },
            accentColor = accentColor,
        )

        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_text_outline),
            enabled = performanceHudConfig.showTextOutline,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showTextOutline = !performanceHudConfig.showTextOutline),
                )
            },
            accentColor = accentColor,
        )

        Spacer(modifier = Modifier.height(8.dp))

        QuickMenuSectionHeader(
            title = stringResource(R.string.performance_hud_metrics),
        )

        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_frame_rate),
            enabled = performanceHudConfig.showFrameRate,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showFrameRate = !performanceHudConfig.showFrameRate),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_frame_rate_graph),
            enabled = performanceHudConfig.showFrameRateGraph,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showFrameRateGraph = !performanceHudConfig.showFrameRateGraph),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_cpu_usage),
            enabled = performanceHudConfig.showCpuUsage,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showCpuUsage = !performanceHudConfig.showCpuUsage),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_cpu_usage_graph),
            enabled = performanceHudConfig.showCpuUsageGraph,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showCpuUsageGraph = !performanceHudConfig.showCpuUsageGraph),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_gpu_usage),
            enabled = performanceHudConfig.showGpuUsage,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showGpuUsage = !performanceHudConfig.showGpuUsage),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_gpu_usage_graph),
            enabled = performanceHudConfig.showGpuUsageGraph,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showGpuUsageGraph = !performanceHudConfig.showGpuUsageGraph),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_ram_usage),
            enabled = performanceHudConfig.showRamUsage,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showRamUsage = !performanceHudConfig.showRamUsage),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_battery_level),
            enabled = performanceHudConfig.showBatteryLevel,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showBatteryLevel = !performanceHudConfig.showBatteryLevel),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_power_draw),
            enabled = performanceHudConfig.showPowerDraw,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showPowerDraw = !performanceHudConfig.showPowerDraw),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_runtime_left),
            enabled = performanceHudConfig.showBatteryRuntime,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showBatteryRuntime = !performanceHudConfig.showBatteryRuntime),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_battery_temperature),
            enabled = performanceHudConfig.showBatteryTemperature,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showBatteryTemperature = !performanceHudConfig.showBatteryTemperature),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_clock_time),
            enabled = performanceHudConfig.showClockTime,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showClockTime = !performanceHudConfig.showClockTime),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_cpu_temperature),
            enabled = performanceHudConfig.showCpuTemperature,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showCpuTemperature = !performanceHudConfig.showCpuTemperature),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_gpu_temperature),
            enabled = performanceHudConfig.showGpuTemperature,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showGpuTemperature = !performanceHudConfig.showGpuTemperature),
                )
            },
            accentColor = accentColor,
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun LsfgQuickMenuTab(
    multiplier: Int,
    flowScale: Float,
    performanceMode: Boolean,
    onMultiplierChanged: (Int) -> Unit,
    onFlowScaleChanged: (Float) -> Unit,
    onPerformanceModeChanged: (Boolean) -> Unit,
    scrollState: ScrollState,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val accentColor = PluviaTheme.colors.accentPurple
    val isEnabled = multiplier >= 2

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── Multiplier (Off / 2x / 3x / 4x) ───────────────────────────────
        QuickMenuSectionHeader(
            title = stringResource(R.string.lsfg_multiplier),
        )
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(0, 2, 3, 4).forEach { value ->
                QuickMenuChoiceChip(
                    text = if (value == 0) "Off" else "${value}x",
                    selected = multiplier == value || (value == 0 && multiplier < 2),
                    accentColor = accentColor,
                    onClick = { onMultiplierChanged(value) },
                    modifier = Modifier.width(56.dp),
                    focusRequester = if (value == 0) focusRequester else null,
                )
            }
        }

        AnimatedVisibility(
            visible = isEnabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Spacer(modifier = Modifier.height(4.dp))

                // ── Flow Scale ────────────────────────────────────────────
                QuickMenuAdjustmentRow(
                    title = stringResource(R.string.lsfg_flow_scale),
                    subtitle = stringResource(R.string.lsfg_flow_scale_desc),
                    valueText = String.format(java.util.Locale.US, "%.2f", flowScale),
                    progress = (flowScale - 0.25f) / 0.75f, // 0.25..1.0 → 0..1
                    onDecrease = {
                        val next = (flowScale - 0.05f).coerceIn(0.25f, 1.0f)
                        onFlowScaleChanged(String.format(java.util.Locale.US, "%.2f", next).toFloat())
                    },
                    onIncrease = {
                        val next = (flowScale + 0.05f).coerceIn(0.25f, 1.0f)
                        onFlowScaleChanged(String.format(java.util.Locale.US, "%.2f", next).toFloat())
                    },
                    accentColor = accentColor,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // ── Performance Mode ──────────────────────────────────────
                QuickMenuToggleRow(
                    title = stringResource(R.string.lsfg_performance_mode),
                    subtitle = stringResource(R.string.lsfg_performance_mode_desc),
                    enabled = performanceMode,
                    onToggle = { onPerformanceModeChanged(!performanceMode) },
                    accentColor = accentColor,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun QuickMenuSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuickMenuCloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .size(44.dp)
            .then(
                if (isFocused) {
                    Modifier.border(
                        BorderStroke(
                            2.dp,
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary,
                                ),
                            ),
                        ),
                        shape,
                    )
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .background(
                if (isFocused) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.quick_menu_back),
            tint = if (isFocused) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun QuickMenuTabButton(
    icon: ImageVector,
    contentDescriptionResId: Int,
    selected: Boolean,
    accentColor: Color,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .size(56.dp)
            .then(
                if (isFocused) {
                    Modifier.border(
                        BorderStroke(
                            2.dp,
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary,
                                ),
                            ),
                        ),
                        shape,
                    )
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .background(
                when {
                    selected -> accentColor.copy(alpha = 0.18f)
                    isFocused -> accentColor.copy(alpha = 0.12f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                },
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .onFocusChanged {
                if (it.isFocused && !selected) {
                    onSelected()
                }
            }
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                onClick = onSelected,
            )
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(contentDescriptionResId),
            tint = when {
                selected || isFocused -> accentColor
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun QuickMenuRailActionButton(
    item: QuickMenuItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accentColor = if (item.accentColor != Color.Unspecified) {
        item.accentColor
    } else {
        MaterialTheme.colorScheme.error
    }
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .size(56.dp)
            .then(
                if (isFocused) {
                    Modifier.border(
                        width = 2.dp,
                        color = accentColor.copy(alpha = 0.7f),
                        shape = shape,
                    )
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        shape = shape,
                    )
                }
            )
            .clip(shape)
            .background(
                if (isFocused) {
                    accentColor.copy(alpha = 0.18f)
                } else {
                    accentColor.copy(alpha = 0.08f)
                },
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = stringResource(item.labelResId),
            tint = if (isFocused) accentColor else accentColor.copy(alpha = 0.9f),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun QuickMenuChoiceChip(
    text: String,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .height(44.dp)
            .then(
                // Only show the focus ring when NOT selected, so a chip that is both
                // selected and focused doesn't stack the fill + heavier border and
                // read as "double-selected" (e.g. the default-focused first sort chip).
                if (isFocused && !selected) {
                    Modifier.border(
                        width = 2.dp,
                        color = accentColor.copy(alpha = 0.7f),
                        shape = shape,
                    )
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = if (selected) accentColor.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        shape = shape,
                    )
                }
            )
            .clip(shape)
            .background(
                when {
                    selected -> accentColor.copy(alpha = 0.18f)
                    isFocused -> accentColor.copy(alpha = 0.12f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                },
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected || isFocused) accentColor else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected || isFocused) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun QuickMenuAdjustmentRow(
    title: String,
    valueText: String,
    progress: Float,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    subtitle: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    var isAdjustmentLocked by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(shape)
            .background(
                if (isFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.16f),
                            accentColor.copy(alpha = 0.08f),
                        ),
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f),
                        ),
                    )
                },
            )
            .then(
                if (isFocused && !isAdjustmentLocked) {
                    Modifier.border(
                        width = 2.dp,
                        color = accentColor.copy(alpha = 0.7f),
                        shape = shape,
                    )
                } else {
                    Modifier
                }
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .onFocusChanged {
                if (!it.isFocused) {
                    isAdjustmentLocked = false
                }
            }
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && isFocused) {
                    when {
                        keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_A -> {
                            isAdjustmentLocked = !isAdjustmentLocked
                            true
                        }

                        isAdjustmentLocked && keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_B -> {
                            isAdjustmentLocked = false
                            true
                        }

                        isAdjustmentLocked && keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                            onDecrease()
                            true
                        }

                        isAdjustmentLocked && keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onIncrease()
                            true
                        }

                        else -> false
                    }
                } else {
                    false
                }
            }
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isFocused) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isAdjustmentLocked) {
                    Text(
                        text = "●",
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                    )
                }
            }
        }

        if (subtitle != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QuickMenuAdjustmentButton(
                text = "-",
                rowIsFocused = isFocused,
                isAdjustmentLocked = isAdjustmentLocked,
                accentColor = accentColor,
                onClick = onDecrease,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = accentColor,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                )

                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onDecrease,
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onIncrease,
                            ),
                    )
                }
            }

            QuickMenuAdjustmentButton(
                text = "+",
                rowIsFocused = isFocused,
                isAdjustmentLocked = isAdjustmentLocked,
                accentColor = accentColor,
                onClick = onIncrease,
            )
        }
    }
}

@Composable
private fun QuickMenuAdjustmentButton(
    text: String,
    rowIsFocused: Boolean,
    isAdjustmentLocked: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(44.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isAdjustmentLocked) {
                    accentColor.copy(alpha = 0.25f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (rowIsFocused) 0.32f else 0.45f)
                },
            )
            .border(
                width = if (isAdjustmentLocked) 2.dp else 1.dp,
                color = if (isAdjustmentLocked) {
                    accentColor.copy(alpha = 0.9f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                },
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (isAdjustmentLocked) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QuickMenuToggleRow(
    title: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.16f),
                            accentColor.copy(alpha = 0.08f),
                        ),
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f),
                        ),
                    )
                },
            )
            .then(
                if (isFocused) {
                    Modifier.border(
                        width = 2.dp,
                        color = accentColor.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(14.dp),
                    )
                } else {
                    Modifier
                }
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onToggle,
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        QuickMenuSwitch(
            enabled = enabled,
            accentColor = accentColor,
        )
    }
}

@Composable
private fun QuickMenuSwitch(
    enabled: Boolean,
    accentColor: Color,
) {
    Box(
        modifier = Modifier
            .width(56.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (enabled) accentColor else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            )
            .border(
                width = 1.dp,
                color = if (enabled) accentColor.copy(alpha = 0.8f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                shape = RoundedCornerShape(999.dp),
            )
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .align(if (enabled) Alignment.CenterEnd else Alignment.CenterStart)
                .background(Color.White, CircleShape),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickMenuProcessRow(
    title: String,
    subtitle: String,
    accentColor: Color,
    onEndProcess: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(shape)
            .background(
                if (isFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.14f),
                            accentColor.copy(alpha = 0.06f),
                        ),
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f),
                        ),
                    )
                },
            )
            .then(
                if (isFocused) {
                    Modifier.border(
                        width = 2.dp,
                        color = accentColor.copy(alpha = 0.8f),
                        shape = shape,
                    )
                } else {
                    Modifier
                }
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && isFocused) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_BUTTON_A,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER -> {
                            onEndProcess()
                            true
                        }

                        else -> false
                    }
                } else {
                    false
                }
            }
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onEndProcess,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isFocused) accentColor else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(iterations = Int.MAX_VALUE),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isFocused) accentColor.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QuickMenuItemRow(
    item: QuickMenuItem,
    isActive: Boolean = false,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    secondaryIcon: ImageVector? = null,
    onSecondaryClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isEnabled = item.enabled

    val accentColor = if (item.accentColor != Color.Unspecified) {
        item.accentColor
    } else {
        MaterialTheme.colorScheme.primary
    }

    val disabledAlpha = 0.4f
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isFocused && isEnabled) {
                    Modifier.border(
                        BorderStroke(
                            2.dp,
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary,
                                ),
                            ),
                        ),
                        shape,
                    )
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .then(
                if (isFocused && isEnabled) {
                    Modifier.background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.15f),
                                accentColor.copy(alpha = 0.05f),
                            ),
                        ),
                    )
                } else {
                    Modifier
                }
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .selectable(
                selected = isFocused,
                enabled = isEnabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable(
                enabled = isEnabled,
                interactionSource = interactionSource,
            )
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .then(
                    if (isActive) {
                        Modifier.border(BorderStroke(2.dp, accentColor), CircleShape)
                    } else Modifier
                )
                .clip(CircleShape)
                .background(
                    when {
                        !isEnabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        isFocused || isActive -> accentColor.copy(alpha = 0.2f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = when {
                    !isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha)
                    isFocused || isActive -> accentColor
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
        }

        Text(
            text = stringResource(item.labelResId),
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !isEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
                isFocused -> accentColor
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )

        if (secondaryIcon != null && onSecondaryClick != null) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable(role = Role.Button, onClick = onSecondaryClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = secondaryIcon,
                    contentDescription = stringResource(R.string.gesture_settings_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun Preview_QuickMenu() {
    PluviaTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            QuickMenu(
                isVisible = true,
                onDismiss = {},
                onItemSelected = { false },
                hasPhysicalController = false,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun Preview_QuickMenu_WithController() {
    PluviaTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            QuickMenu(
                isVisible = true,
                onDismiss = {},
                onItemSelected = { false },
                hasPhysicalController = true,
            )
        }
    }
}
