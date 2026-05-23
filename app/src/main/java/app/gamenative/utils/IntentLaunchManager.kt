package app.gamenative.utils

import android.content.Context
import android.content.Intent
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.core.DXVKHelper
import org.json.JSONObject
import timber.log.Timber

/**
 * Handles external game launch intents with container configuration overrides.
 */
object IntentLaunchManager {

    private const val EXTRA_APP_ID = "app_id"

    private const val EXTRA_GAME_SOURCE = "game_source"
    private const val EXTRA_CONTAINER_CONFIG = "container_config"
    private const val ACTION_LAUNCH_GAME = "app.gamenative.LAUNCH_GAME"
    const val ACTION_OPEN_GAME_PAGE = "app.gamenative.OPEN_GAME_PAGE"
    private const val MAX_CONFIG_JSON_SIZE = 50000 // 50KB limit to prevent memory exhaustion

    data class LaunchRequest(
        val appId: String,
        val containerConfig: ContainerData? = null,
    )

    fun parseLaunchIntent(intent: Intent): LaunchRequest? {
        Timber.d("[IntentLaunchManager]: Parsing intent: action=${intent.action}")

        // Handle steam://run/{appId}, steam://rungameid/{appId}, steam://launch/{appId} deep links.
        // Android parses steam://run/440 as scheme=steam, host=run, pathSegments=["440"].
        if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "steam") {
            val host = intent.data?.host
            if (host == "run" || host == "rungameid" || host == "launch") {
                val appIdNum = intent.data?.pathSegments?.firstOrNull()?.toIntOrNull()
                if (appIdNum != null && appIdNum > 0) {
                    Timber.d("[IntentLaunchManager]: steam:// launch for appId: STEAM_$appIdNum")
                    return LaunchRequest("STEAM_$appIdNum")
                }
                Timber.w("[IntentLaunchManager]: steam:// URL missing valid appId: ${intent.data}")
                return null
            }
        }

        if (intent.action != ACTION_LAUNCH_GAME) {
            Timber.d("[IntentLaunchManager]: Intent action '${intent.action}' doesn't match expected action '$ACTION_LAUNCH_GAME'")
            return null
        }

        val gameId = intent.getIntExtra(EXTRA_APP_ID, -1)
        Timber.d("[IntentLaunchManager]: Extracted app_id: $gameId from intent extras")

        if (gameId <= 0) {
            Timber.w("[IntentLaunchManager]: Invalid or missing app_id in launch intent: $gameId")
            return null
        }

        // Get Game Source for launch intent
        var gameSource = intent.getStringExtra(EXTRA_GAME_SOURCE)?.uppercase(java.util.Locale.ROOT)
        val isValidGameSource = GameSource.entries.any { it.name == gameSource }
        if (!isValidGameSource) {
            gameSource = GameSource.STEAM.name
        }

        val appId = "${gameSource}_$gameId"
        Timber.d("[IntentLaunchManager]: Converted to appId: $appId")

        val containerConfigJson = intent.getStringExtra(EXTRA_CONTAINER_CONFIG)
        val containerConfig = if (containerConfigJson != null) {
            try {
                parseContainerConfig(containerConfigJson)
            } catch (e: Exception) {
                Timber.e(e, "[IntentLaunchManager]: Failed to parse container configuration JSON")
                null
            }
        } else {
            null
        }

        return LaunchRequest(appId, containerConfig)
    }

    fun parseOpenPageIntent(intent: Intent): String? {
        if (intent.action != ACTION_OPEN_GAME_PAGE) return null

        val gameId = intent.getIntExtra(EXTRA_APP_ID, -1)
        if (gameId <= 0) {
            Timber.w("[IntentLaunchManager]: OPEN_GAME_PAGE intent missing valid app_id: $gameId")
            return null
        }

        var gameSource = intent.getStringExtra(EXTRA_GAME_SOURCE)?.uppercase(java.util.Locale.ROOT)
        if (GameSource.entries.none { it.name == gameSource }) {
            gameSource = GameSource.STEAM.name
        }

        return "${gameSource}_$gameId"
    }

    fun applyTemporaryConfigOverride(context: Context, appId: String, configOverride: ContainerData) {
        try {
            TemporaryConfigStore.setOverride(appId, configOverride)

            if (ContainerUtils.hasContainer(context, appId)) {
                val container = ContainerUtils.getContainer(context, appId)

                // Backup original config before applying override (only once)
                if (TemporaryConfigStore.getOriginalConfig(appId) == null) {
                    val originalConfig = ContainerUtils.toContainerData(container)
                    TemporaryConfigStore.setOriginalConfig(appId, originalConfig)
                }

                // Get the effective config (merge base with override)
                val effectiveConfig = getEffectiveContainerConfig(context, appId)
                if (effectiveConfig != null) {
                    ContainerUtils.applyToContainer(context, container, effectiveConfig, saveToDisk = false)
                    Timber.i("[IntentLaunchManager]: Applied temporary config override for app $appId (in-memory only)")
                }
            } else {
                Timber.i("[IntentLaunchManager]: Stored temporary config override for app $appId (container will be created on launch)")
            }
        } catch (e: Exception) {
            Timber.e(e, "[IntentLaunchManager]: Failed to apply temporary config override for app $appId")
            throw e
        }
    }

    fun getEffectiveContainerConfig(context: Context, appId: String): ContainerData? {
        return try {
            val baseConfig = if (ContainerUtils.hasContainer(context, appId)) {
                val container = ContainerUtils.getContainer(context, appId)
                ContainerUtils.toContainerData(container)
            } else {
                null
            }

            val override = TemporaryConfigStore.getOverride(appId)

            when {
                override != null && baseConfig != null -> mergeConfigurations(baseConfig, override)
                override != null -> override
                else -> baseConfig
            }
        } catch (e: Exception) {
            Timber.e(e, "[IntentLaunchManager]: Failed to get effective container config for app $appId")
            null
        }
    }

    fun clearTemporaryOverride(appId: String) {
        TemporaryConfigStore.clearOverride(appId)
        Timber.d("[IntentLaunchManager]: Cleared temporary config override for app $appId")
    }

    fun clearAllTemporaryOverrides() {
        TemporaryConfigStore.clearAll()
        Timber.d("[IntentLaunchManager]: Cleared all temporary config overrides")
    }

    fun restoreOriginalConfiguration(context: Context, appId: String) {
        try {
            val originalConfig = TemporaryConfigStore.getOriginalConfig(appId)
            if (originalConfig != null && ContainerUtils.hasContainer(context, appId)) {
                val container = ContainerUtils.getContainer(context, appId)
                ContainerUtils.applyToContainer(context, container, originalConfig, saveToDisk = false)
                Timber.i("[IntentLaunchManager]: Restored original configuration for app $appId")
            }
        } catch (e: Exception) {
            Timber.e(e, "[IntentLaunchManager]: Failed to restore original configuration for app $appId")
        }
    }

    fun hasTemporaryOverride(appId: String): Boolean {
        return TemporaryConfigStore.hasOverride(appId)
    }

    fun getTemporaryOverride(appId: String): ContainerData? {
        return TemporaryConfigStore.getOverride(appId)
    }

    fun getOriginalConfig(appId: String): ContainerData? {
        return TemporaryConfigStore.getOriginalConfig(appId)
    }

    fun setOriginalConfig(appId: String, config: ContainerData) {
        TemporaryConfigStore.setOriginalConfig(appId, config)
    }

    private fun validateContainerConfig(config: ContainerData): List<String> {
        val issues = mutableListOf<String>()

        if (!config.screenSize.matches(Regex("\\d+x\\d+"))) {
            issues.add("Invalid screen size format: ${config.screenSize}. Expected format: WIDTHxHEIGHT (e.g., 1920x1080)")
        }

        if (config.cpuList.isNotEmpty() && !config.cpuList.matches(Regex("\\d+(,\\d+)*"))) {
            issues.add("Invalid CPU list format: ${config.cpuList}. Expected comma-separated numbers (e.g., 0,1,2,3)")
        }

        if (!config.videoMemorySize.matches(Regex("\\d+"))) {
            issues.add("Invalid video memory size: ${config.videoMemorySize}. Expected numeric value in MB")
        }

        if (config.drives.isNotEmpty() && !config.drives.matches(Regex("([A-Z]:([^:]+))*"))) {
            issues.add("Invalid drives format: ${config.drives}. Expected format: LETTER:PATH (e.g., C:/path/to/drive)")
        }

        return issues
    }

    private fun parseContainerConfig(jsonString: String): ContainerData {
        if (jsonString.length > MAX_CONFIG_JSON_SIZE) {
            throw IllegalArgumentException("Container configuration JSON too large (max ${MAX_CONFIG_JSON_SIZE / 1000}KB)")
        }

        val json = JSONObject(jsonString)

        // Only include non-default values to avoid overriding existing container settings
        val config = ContainerData(
            name = if (json.has("name")) json.getString("name") else "",
            screenSize = if (json.has("screenSize")) json.getString("screenSize") else Container.DEFAULT_SCREEN_SIZE,
            envVars = if (json.has("envVars")) json.getString("envVars") else Container.DEFAULT_ENV_VARS,
            graphicsDriver = if (json.has("graphicsDriver")) json.getString("graphicsDriver") else Container.DEFAULT_GRAPHICS_DRIVER,
            graphicsDriverVersion = if (json.has("graphicsDriverVersion")) json.getString("graphicsDriverVersion") else "",
            dxwrapper = if (json.has("dxwrapper")) json.getString("dxwrapper") else Container.DEFAULT_DXWRAPPER,
            dxwrapperConfig = if (json.has("dxwrapperConfig")) {
                "version=" + json.getString("dxwrapperConfig")
            } else {
                ""
            },
            audioDriver = if (json.has("audioDriver")) json.getString("audioDriver") else Container.DEFAULT_AUDIO_DRIVER,
            wincomponents = if (json.has("wincomponents")) json.getString("wincomponents") else Container.DEFAULT_WINCOMPONENTS,
            drives = if (json.has("drives")) json.getString("drives") else Container.DEFAULT_DRIVES,
            execArgs = if (json.has("execArgs")) json.getString("execArgs") else "",
            executablePath = if (json.has("executablePath")) json.getString("executablePath") else "",
            installPath = if (json.has("installPath")) json.getString("installPath") else "",
            showFPS = if (json.has("showFPS")) json.getBoolean("showFPS") else false,
            launchRealSteam = if (json.has("launchRealSteam")) json.getBoolean("launchRealSteam") else false,
            launchBionicSteam = if (json.has("launchBionicSteam")) json.getBoolean("launchBionicSteam") else false,
            cpuList = if (json.has("cpuList")) json.getString("cpuList") else Container.getFallbackCPUList(),
            cpuListWoW64 = if (json.has("cpuListWoW64")) json.getString("cpuListWoW64") else Container.getFallbackCPUListWoW64(),
            wow64Mode = if (json.has("wow64Mode")) json.getBoolean("wow64Mode") else true,
            startupSelection = if (json.has("startupSelection")) {
                json.getInt("startupSelection").toByte()
            } else {
                Container.STARTUP_SELECTION_ESSENTIAL.toInt().toByte()
            },
            box86Version = if (json.has("box86Version")) json.getString("box86Version") else "",
            box64Version = if (json.has("box64Version")) json.getString("box64Version") else "",
            box86Preset = if (json.has("box86Preset")) json.getString("box86Preset") else "",
            box64Preset = if (json.has("box64Preset")) json.getString("box64Preset") else "",
            desktopTheme = if (json.has("desktopTheme")) json.getString("desktopTheme") else "",
            csmt = if (json.has("csmt")) json.getBoolean("csmt") else true,
            videoPciDeviceID = if (json.has("videoPciDeviceID")) json.getInt("videoPciDeviceID") else 1728,
            offScreenRenderingMode = if (json.has("offScreenRenderingMode")) json.getString("offScreenRenderingMode") else "fbo",
            strictShaderMath = if (json.has("strictShaderMath")) json.getBoolean("strictShaderMath") else true,
            videoMemorySize = if (json.has("videoMemorySize")) json.getString("videoMemorySize") else "2048",
            mouseWarpOverride = if (json.has("mouseWarpOverride")) json.getString("mouseWarpOverride") else "disable",
            sdlControllerAPI = if (json.has("sdlControllerAPI")) json.getBoolean("sdlControllerAPI") else true,
            enableXInput = if (json.has("enableXInput")) json.getBoolean("enableXInput") else true,
            enableDInput = if (json.has("enableDInput")) json.getBoolean("enableDInput") else true,
            dinputMapperType = if (json.has("dinputMapperType")) {
                json.getInt("dinputMapperType").toByte()
            } else {
                1.toByte()
            },
            disableMouseInput = if (json.has("disableMouseInput")) json.getBoolean("disableMouseInput") else false,
            suspendPolicy = if (json.has("suspendPolicy")) {
                Container.normalizeSuspendPolicy(json.getString("suspendPolicy"))
            } else {
                PrefManager.suspendPolicy
            },
            shaderBackend = if (json.has("shaderBackend")) json.getString("shaderBackend") else "glsl",
            useGLSL = if (json.has("useGLSL")) json.getString("useGLSL") else "enabled",
        )

        val validationIssues = validateContainerConfig(config)
        if (validationIssues.isNotEmpty()) {
            Timber.w("[IntentLaunchManager]: Container configuration validation issues: ${validationIssues.joinToString("; ")}")
        }

        return config
    }

    private fun mergeConfigurations(base: ContainerData, override: ContainerData): ContainerData {
        // Quick return if no actual overrides
        if (override == base) return base

        // Start from a copy of the base so any field NOT listed below keeps its base value.
        // For each field we apply the override only when it differs from the ContainerData default,
        // treating "equals default" as "not explicitly set by the override caller."
        // This is imperfect for the edge case where the caller intentionally sets a field to its
        // default value, but correct for the common case and far safer than the previous approach
        // which silently reset ~27 fields (emulator, wineVersion, language, etc.) to defaults.
        val d = ContainerData() // default sentinel
        return base.copy(
            name                  = if (override.name                  != d.name)                  override.name                  else base.name,
            screenSize            = if (override.screenSize            != d.screenSize)            override.screenSize            else base.screenSize,
            envVars               = if (override.envVars               != d.envVars)               override.envVars               else base.envVars,
            graphicsDriver        = if (override.graphicsDriver        != d.graphicsDriver)        override.graphicsDriver        else base.graphicsDriver,
            graphicsDriverVersion = if (override.graphicsDriverVersion != d.graphicsDriverVersion) override.graphicsDriverVersion else base.graphicsDriverVersion,
            graphicsDriverConfig  = if (override.graphicsDriverConfig  != d.graphicsDriverConfig)  override.graphicsDriverConfig  else base.graphicsDriverConfig,
            dxwrapper             = if (override.dxwrapper             != d.dxwrapper)             override.dxwrapper             else base.dxwrapper,
            dxwrapperConfig       = if (override.dxwrapperConfig       != d.dxwrapperConfig)       override.dxwrapperConfig       else base.dxwrapperConfig,
            audioDriver           = if (override.audioDriver           != d.audioDriver)           override.audioDriver           else base.audioDriver,
            wincomponents         = if (override.wincomponents         != d.wincomponents)         override.wincomponents         else base.wincomponents,
            drives                = if (override.drives                != d.drives)                override.drives                else base.drives,
            execArgs              = if (override.execArgs              != d.execArgs)              override.execArgs              else base.execArgs,
            executablePath        = if (override.executablePath        != d.executablePath)        override.executablePath        else base.executablePath,
            installPath           = if (override.installPath           != d.installPath)           override.installPath           else base.installPath,
            showFPS               = if (override.showFPS               != d.showFPS)               override.showFPS               else base.showFPS,
            launchRealSteam       = if (override.launchRealSteam       != d.launchRealSteam)       override.launchRealSteam       else base.launchRealSteam,
            launchBionicSteam     = if (override.launchBionicSteam     != d.launchBionicSteam)     override.launchBionicSteam     else base.launchBionicSteam,
            allowSteamUpdates     = if (override.allowSteamUpdates     != d.allowSteamUpdates)     override.allowSteamUpdates     else base.allowSteamUpdates,
            steamType             = if (override.steamType             != d.steamType)             override.steamType             else base.steamType,
            cpuList               = if (override.cpuList               != d.cpuList)               override.cpuList               else base.cpuList,
            cpuListWoW64          = if (override.cpuListWoW64          != d.cpuListWoW64)          override.cpuListWoW64          else base.cpuListWoW64,
            wow64Mode             = if (override.wow64Mode             != d.wow64Mode)             override.wow64Mode             else base.wow64Mode,
            startupSelection      = if (override.startupSelection      != d.startupSelection)      override.startupSelection      else base.startupSelection,
            box86Version          = if (override.box86Version          != d.box86Version)          override.box86Version          else base.box86Version,
            box64Version          = if (override.box64Version          != d.box64Version)          override.box64Version          else base.box64Version,
            box86Preset           = if (override.box86Preset           != d.box86Preset)           override.box86Preset           else base.box86Preset,
            box64Preset           = if (override.box64Preset           != d.box64Preset)           override.box64Preset           else base.box64Preset,
            desktopTheme          = if (override.desktopTheme          != d.desktopTheme)          override.desktopTheme          else base.desktopTheme,
            containerVariant      = if (override.containerVariant      != d.containerVariant)      override.containerVariant      else base.containerVariant,
            wineVersion           = if (override.wineVersion           != d.wineVersion)           override.wineVersion           else base.wineVersion,
            emulator              = if (override.emulator              != d.emulator)              override.emulator              else base.emulator,
            fexcoreVersion        = if (override.fexcoreVersion        != d.fexcoreVersion)        override.fexcoreVersion        else base.fexcoreVersion,
            fexcoreTSOMode        = if (override.fexcoreTSOMode        != d.fexcoreTSOMode)        override.fexcoreTSOMode        else base.fexcoreTSOMode,
            fexcoreX87Mode        = if (override.fexcoreX87Mode        != d.fexcoreX87Mode)        override.fexcoreX87Mode        else base.fexcoreX87Mode,
            fexcoreMultiBlock     = if (override.fexcoreMultiBlock     != d.fexcoreMultiBlock)     override.fexcoreMultiBlock     else base.fexcoreMultiBlock,
            fexcorePreset         = if (override.fexcorePreset         != d.fexcorePreset)         override.fexcorePreset         else base.fexcorePreset,
            renderer              = if (override.renderer              != d.renderer)              override.renderer              else base.renderer,
            csmt                  = if (override.csmt                  != d.csmt)                  override.csmt                  else base.csmt,
            videoPciDeviceID      = if (override.videoPciDeviceID      != d.videoPciDeviceID)      override.videoPciDeviceID      else base.videoPciDeviceID,
            offScreenRenderingMode= if (override.offScreenRenderingMode!= d.offScreenRenderingMode)override.offScreenRenderingMode else base.offScreenRenderingMode,
            strictShaderMath      = if (override.strictShaderMath      != d.strictShaderMath)      override.strictShaderMath      else base.strictShaderMath,
            useDRI3               = if (override.useDRI3               != d.useDRI3)               override.useDRI3               else base.useDRI3,
            videoMemorySize       = if (override.videoMemorySize       != d.videoMemorySize)       override.videoMemorySize       else base.videoMemorySize,
            mouseWarpOverride     = if (override.mouseWarpOverride     != d.mouseWarpOverride)     override.mouseWarpOverride     else base.mouseWarpOverride,
            shaderBackend         = if (override.shaderBackend         != d.shaderBackend)         override.shaderBackend         else base.shaderBackend,
            useGLSL               = if (override.useGLSL               != d.useGLSL)               override.useGLSL               else base.useGLSL,
            sdlControllerAPI      = if (override.sdlControllerAPI      != d.sdlControllerAPI)      override.sdlControllerAPI      else base.sdlControllerAPI,
            useSteamInput         = if (override.useSteamInput         != d.useSteamInput)         override.useSteamInput         else base.useSteamInput,
            enableXInput          = if (override.enableXInput          != d.enableXInput)          override.enableXInput          else base.enableXInput,
            enableDInput          = if (override.enableDInput          != d.enableDInput)          override.enableDInput          else base.enableDInput,
            dinputMapperType      = if (override.dinputMapperType      != d.dinputMapperType)      override.dinputMapperType      else base.dinputMapperType,
            disableMouseInput     = if (override.disableMouseInput     != d.disableMouseInput)     override.disableMouseInput     else base.disableMouseInput,
            touchscreenMode       = if (override.touchscreenMode       != d.touchscreenMode)       override.touchscreenMode       else base.touchscreenMode,
            shooterMode           = if (override.shooterMode           != d.shooterMode)           override.shooterMode           else base.shooterMode,
            gestureConfig         = if (override.gestureConfig         != d.gestureConfig)         override.gestureConfig         else base.gestureConfig,
            externalDisplayMode   = if (override.externalDisplayMode   != d.externalDisplayMode)   override.externalDisplayMode   else base.externalDisplayMode,
            externalDisplaySwap   = if (override.externalDisplaySwap   != d.externalDisplaySwap)   override.externalDisplaySwap   else base.externalDisplaySwap,
            language              = if (override.language              != d.language)              override.language              else base.language,
            forceDlc              = if (override.forceDlc              != d.forceDlc)              override.forceDlc              else base.forceDlc,
            localSavesOnly        = if (override.localSavesOnly        != d.localSavesOnly)        override.localSavesOnly        else base.localSavesOnly,
            steamOfflineMode      = if (override.steamOfflineMode      != d.steamOfflineMode)      override.steamOfflineMode      else base.steamOfflineMode,
            useLegacyDRM          = if (override.useLegacyDRM          != d.useLegacyDRM)          override.useLegacyDRM          else base.useLegacyDRM,
            unpackFiles           = if (override.unpackFiles           != d.unpackFiles)           override.unpackFiles           else base.unpackFiles,
            suspendPolicy         = if (override.suspendPolicy         != d.suspendPolicy)         override.suspendPolicy         else base.suspendPolicy,
            portraitMode          = if (override.portraitMode          != d.portraitMode)          override.portraitMode          else base.portraitMode,
            verticalMode          = if (override.verticalMode          != d.verticalMode)          override.verticalMode          else base.verticalMode,
            sharpnessEffect       = if (override.sharpnessEffect       != d.sharpnessEffect)       override.sharpnessEffect       else base.sharpnessEffect,
            sharpnessLevel        = if (override.sharpnessLevel        != d.sharpnessLevel)        override.sharpnessLevel        else base.sharpnessLevel,
            sharpnessDenoise      = if (override.sharpnessDenoise      != d.sharpnessDenoise)      override.sharpnessDenoise      else base.sharpnessDenoise,
            lsfgEnabled           = if (override.lsfgEnabled           != d.lsfgEnabled)           override.lsfgEnabled           else base.lsfgEnabled,
        )
    }

    // Lightweight store for +connect_lobby args injected by the game invite flow.
    // Separate from ContainerData overrides to avoid clobbering any container settings.
    private val pendingExtraGameArgs = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun setPendingExtraGameArgs(appId: String, args: String) {
        pendingExtraGameArgs[appId] = args
    }

    fun consumePendingExtraGameArgs(appId: String): String? =
        pendingExtraGameArgs.remove(appId)
}

private object TemporaryConfigStore {
    private val overrides = mutableMapOf<String, ContainerData>()
    private val originalConfigs = mutableMapOf<String, ContainerData>()
    private val lock = Any()

    fun setOverride(appId: String, config: ContainerData) = synchronized(lock) {
        overrides[appId] = config
    }

    fun getOverride(appId: String): ContainerData? = synchronized(lock) {
        overrides[appId]
    }

    fun clearOverride(appId: String) = synchronized(lock) {
        overrides.remove(appId)
        originalConfigs.remove(appId)
    }

    fun hasOverride(appId: String): Boolean = synchronized(lock) {
        overrides.containsKey(appId)
    }

    fun setOriginalConfig(appId: String, config: ContainerData) = synchronized(lock) {
        originalConfigs[appId] = config
    }

    fun getOriginalConfig(appId: String): ContainerData? = synchronized(lock) {
        originalConfigs[appId]
    }

    fun clearAll() = synchronized(lock) {
        overrides.clear()
        originalConfigs.clear()
    }
}
