# GameNative

## Project Overview

GameNative (`app.gamenative`) is an Android app that lets users play PC games from Steam, GOG, Epic Games, and Amazon Games on Android. It aggregates multi-platform game libraries, handles authentication and cloud saves per platform, and launches games through an embedded Windows emulation layer (Winlator/Box86/FEX). This is the user's fork of GameNative that aims to improve optimization for users with large game libraries (i.e., ~45k games on Steam), but may also include other fixes for issues the user decides to implement.

## Response guidelines

The user is an experienced developer but not intimately familiar with the Android SDK, Kotlin, or this codebase. Explain any plan, decision, or other response as if the user is a student looking to familiarize themselves with these topics. When writing code, always include comments around complex portions and that explain idioms in Android, Kotlin, or this codebase.

Your number one priority is to fix the issues the user presents in a performant manner. Database schema changes must only be made as an absolute last resort. It is intended that this personal fork maintain compatibility with upstream in the event that the user needs to revert. If you absolutely must make a schema migration, always confirm with the user first.

Try to keep diffs small where possible in the event that upstream provides fixes to issues the user is encountering and changes need to be reverted. It is also not necessary to apply comments to or clean up existing code unless it impacts performance and/or relates directly to a problem the user is trying to solve.

At the end of every plan, please provide a sample commit message that in a sentence or two summarizes a the changes made and/or problem(s) solved at a high level. Also, if you "learned" anything from exploring the codebase that may be useful to future agents, feel free to suggest changes to this CLAUDE.md file.

## Codebase Guide

### Two Core Namespaces

The Java/Kotlin source has two distinct namespaces with different ownership:

| Namespace | Purpose |
|---|---|
| `app.gamenative.*` | App business logic: UI, services, DB, DI, API |
| `com.winlator.*` | Embedded Windows emulation: X11 server, container management, input controls, rendering |

Do not mix concerns between them. New feature work belongs in `app.gamenative`.

### Architecture

MVVM + clean-ish layering with Hilt DI throughout.

#### UI — `app/src/main/java/app/gamenative/ui/`

- **Framework**: Jetpack Compose (BOM 2025.01.01), Material 3
- **Root**: `PluviaMain.kt` — top-level Compose function called from `MainActivity`
- **Screens**: `ui/screen/` — `auth/`, `library/`, `downloads/`, `settings/`, `login/`, `xserver/`
- **ViewModels**: `ui/model/` — one per screen; all annotated `@HiltViewModel`
  - `MainViewModel` — top-level navigation and Steam event handling
  - `LibraryViewModel` — game list filtering and sorting
  - `DownloadsViewModel` — download queue state
  - `GamePageViewModel` — individual game details
  - `XServerViewModel` — Windows emulation session state
- **Components**: `ui/component/` — reusable composables (dialogs, FAB menu, top bar, settings)
- **Theme**: `ui/theme/` — Material Kolor dynamic theming

#### Services — `app/src/main/java/app/gamenative/service/`

Foreground services (`dataSync` foreground service type) per platform:

| Service | Responsibilities |
|---|---|
| `SteamService.kt` (~182 KB) | Auth (QR + credentials), library sync, depot downloader, cloud saves, friends, achievements, workshop |
| `EpicService.kt` + `epic/` subdirectory | Epic OAuth, library sync, download manager, cloud saves |
| `GOGService.kt` + `gog/` subdirectory | GOG OAuth, library sync |
| `AmazonService.kt` + `amazon/` subdirectory | Amazon auth, library sync |
| `DownloadService.kt` | Shared download queue management |

Services act as the repository layer for platform data.

#### Database — `app/src/main/java/app/gamenative/db/`

- **ORM**: Room 2.8.4; database class `PluviaDatabase` (currently schema v19)
- **Schema migrations**: `app/schemas/` — auto-migrations enabled; destructive fallback for dev
- **DAOs**: `SteamAppDao`, `GOGGameDao`, `EpicGameDao`, `AmazonGameDao`, `SteamLicenseDao`, `CachedLicenseDao`, `AppInfoDao`, `DownloadingAppInfoDao`, and more
- **Entities**: `app/src/main/java/app/gamenative/data/` — `SteamApp`, `GOGGame`, `EpicGame`, `AmazonGame`, `UserFileInfo`, `SteamFriend`, etc.

When making a schema change, increment the schema version in `PluviaDatabase` and add a migration (or auto-migration spec).

#### Dependency Injection — `app/src/main/java/app/gamenative/di/`

- **Framework**: Hilt 2.55, `@InstallIn(SingletonComponent::class)`
- **Modules**: `DatabaseModule` (provides `PluviaDatabase` and all DAOs), `AppThemeModule`
- All ViewModels use constructor injection via `@HiltViewModel`

#### API / Network — `app/src/main/java/app/gamenative/api/`

- **HTTP client**: OkHttp 5.1.0 with DNS-over-HTTPS
- **Backend**: `https://api.gamenative.app` (dev: `http://10.0.2.2:8787`)
- **Clients**: `GameNativeApi`, `GameRunApi`, `SupportersApi`
- **Result type**: `ApiResult` sealed class — `Success`, `HttpError`, `NetworkError`
- **Auth validation**: Play Integrity API (v1.6.0)

### Events — `app/src/main/java/app/gamenative/events/`

- `PluviaApp.events` — static `EventDispatcher` singleton for cross-component communication
- `AndroidEvent` — UI-level signals (library changes, install status)
- `SteamEvent` — Steam connection lifecycle (connected, disconnected, logon)

### Native / C++ — `app/src/main/cpp/`

- **Build**: CMake 3.22.1, NDK 22.1.7171670
- **ABIs**: `arm64-v8a`, `armeabi-v7a`
- **Compiled library** (`libwinlator`): graphics (`drawable.c`, `gpu_image.c`), audio (`alsa_client.c`), shared memory (`sysvshared_memory.c`), X11 (`xconnector_epoll.c`), ELF patching (`patchelf_wrapper.cpp`)
- **Supporting**: `virglrenderer`, `patchelf`, `adrenotools`
- **Prebuilt `.so`**: `app/src/main/jniLibs/` — uses legacy JNI packaging (`useLegacyPackaging = true`)

### Library Screen Hot Path

`LibraryViewModel.onFilterApps()` is called on every user interaction with the library (search, sort, tab switch, install/uninstall events). Key facts for anyone working on this code:

- **`SteamAppDao.getAllOwnedAppSummaries()`** only re-emits when the owned-app *count* changes (driven by `_observeOwnedAppCount()`). Individual field updates from PICS sync — e.g., depot size changes — do **not** trigger a re-emission. This means `appList` in `LibraryViewModel` is stable between count changes, and the `SteamAppSummary` object instances inside it are reused across `onFilterApps()` calls. Reference equality (`===`) is therefore sufficient to detect whether a summary has changed.
- **`SteamAppSummary` is not lightweight** despite being a projection. It still includes `depots: Map<Int, DepotInfo>` and `config: ConfigInfo` blobs. Only `branches` and `ufs` are excluded relative to the full `SteamApp` entity. Do not assume it is cheap to compare structurally.
- **`SteamApp.lastChangeNumber`** is the authoritative PICS change marker; it increments whenever Steam updates an app's metadata. It is not currently projected into `SteamAppSummary` but is useful for invalidation logic if finer-grained change detection is ever needed.
- **Per-item bottleneck**: `SteamService.resolveDownloadableDepots()` + `sumOf()` walks depot manifests for every filtered Steam app on every `onFilterApps()` invocation. As of this writing, `LibraryViewModel.steamItemCache` memoizes this result keyed by Steam app ID, using `SteamAppSummary` reference equality to validate cache entries.

### Game Page Install Flow

getAppInfoOf(appId) (SteamService companion, line 598) is NOT an in-memory cache — it's a DB query via appDao.findApp(appId). All install-path functions (getDownloadableDepots, isValidToDownload) read from the steam_app table.

Stub rows: License processing can write a steam_app row with depot IDs but empty manifests maps before the full PICS product info has been synced. These cause 0 KB install sizes and silent install failures (no manifest GID for the downloader). GamePageViewModel fires SteamService.requestAppInfoNow() on page open to fix this proactively.

On-demand PICS: SteamService.requestAppInfoNow(appId) calls picsGetProductInfo for a single app and writes the result to DB. Pattern mirrors isUpdatePending (line 2727) + the write-back from continuousPICSGetProductInfo (lines 3836–3874).

Install-click stacking: SteamAppScreen launches SteamService.downloadApp via fire-and-forget CoroutineScope(Dispatchers.IO).launch calls (not tied to any ViewModel). The second downloadApp overload guards against duplicate active downloads via downloadJobs.contains() and an atomic pendingDownloads set (ConcurrentHashMap.newKeySet()). The first overload retries with an on-demand PICS fetch (runBlocking { requestAppInfoNow(appId) }) when getDownloadableDepots() returns empty, to handle stub rows created during initial PICS sync; if still empty after the retry it posts a notificationHelper notification.

Two-phase page load: `GamePageViewModel._libraryItem` is set twice for Steam games — once immediately from the cached DB row (Phase 1, before the PICS network round-trip) to render the page instantly, and again after `requestAppInfoNow()` completes (Phase 2) to reflect any updated name, artwork, or `hasWorkshop` flag. `StateFlow` deduplicates equal values, so Phase 2 only triggers recomposition when PICS data actually differs from the cache. `LibraryItem` carries `hasWorkshop` specifically to ensure Phase 2 produces a structurally different struct when the flag changes — without it, phases with the same name/art would be equal and Phase 2 would be silently dropped. `SteamAppScreen.getGameDisplayInfo()` keys its `remember` on the full `libraryItem` object (data class equality) so the `appInfo` DB read re-runs when Phase 2 emits a different `LibraryItem`.

`isValidToDownloadAsync` retry: When `isValidToDownload` returns false (empty depots in DB), `SteamAppScreen.isValidToDownloadAsync` calls `requestAppInfoNow` before calling `getDownloadableDepots`. This mirrors the retry in the first `downloadApp` overload and handles stub rows that arrive before PICS sync completes. Without this, apps with family-shared licenses (which generate early stub rows) can end up with a permanently grayed install button because `BaseAppScreen`'s `LaunchedEffect(libraryItem.appId)` only fires once per app ID and cannot re-run after PICS writes depot data.

`requestAppInfoNow` access tokens: Some apps (e.g. Risk of Rain 2) require a non-zero PICS access token or Steam responds with `isMissingToken=true` and an empty buffer. `generateSteamApp()` on an empty buffer returns `id=Int.MAX_VALUE` (INVALID_APP_ID) and `depots=emptyMap()`, so `appDao.insert()` writes to the wrong row and the stub at the real app ID is never updated. `requestAppInfoNow` calls `picsGetAccessTokens` before `picsGetProductInfo` and passes the token in `PICSRequest(id, accessToken)`, matching the pattern in `bufferedPICSGetProductInfo`. An `isMissingToken` guard after the result prevents inserting a garbage row if the token fetch still wasn't sufficient.

`ConfigInfo` JSON blob: `ConfigInfo` is `@Serializable` and stored as a single JSON column (`config`) in `steam_app` via a Room TypeConverter. Adding a new field with a Kotlin default value is backward-compatible with existing rows — no schema migration needed when extending it.

`onLicenseList` write-lock contention: On large libraries (~59k licenses), `onLicenseList` previously held the Room write lock for minutes inside a single `db.withTransaction` because it serialized all licenses to JSON, ran 60 batched `NOT IN` queries via `findStaleLicences`, and called `packagePicsChannel.send()` — all while the lock was held. The package PICS processor's own `db.withTransaction` calls blocked on this lock, halting the entire PICS pipeline. Fix: do CPU work (groupBy/map) outside the transaction, replace `findStaleLicences`+`deleteStaleLicenses` with `deleteAll`+`insertAll`, commit T1 before queuing to the channel, and defer the `cachedLicenseDao` write (T2) until after queuing.

### Preferences (PrefManager)

**File**: `app/src/main/java/app/gamenative/PrefManager.kt`

Uses Jetpack DataStore (not SharedPreferences). All reads are blocking via `getPref()`; writes are fire-and-forget via `setPref()`. Both helpers are defined in PrefManager and handle the coroutine plumbing internally.

**Adding a preference** — three parts, always together:

```kotlin
// 1. Private key — use the type-appropriate factory (booleanPreferencesKey,
//    stringPreferencesKey, intPreferencesKey, floatPreferencesKey, etc.)
private val MY_PREF = booleanPreferencesKey("my_pref")

// 2 & 3. Public property with getter (supplies default) and setter
var myPref: Boolean
    get() = getPref(MY_PREF, false)   // second arg is the default value
    set(value) {
        setPref(MY_PREF, value)
    }
```

**Exposing in Settings UI** — `SettingsGroupInterface.kt` contains all user-visible settings. Settings are grouped by `SettingsGroup {}` blocks (Downloads, appearance, etc.). The standard pattern for a boolean toggle:

```kotlin
var myPref by rememberSaveable { mutableStateOf(PrefManager.myPref) }
SettingsSwitch(
    colors = settingsTileColorsAlt(),
    title = { Text(text = stringResource(R.string.settings_my_pref_title)) },
    subtitle = { Text(text = stringResource(R.string.settings_my_pref_subtitle)) },
    state = myPref,
    onCheckedChange = {
        myPref = it
        PrefManager.myPref = it
    },
)
```

For dropdowns use `SettingsListDropdown`; for a tappable link use `SettingsMenuLink`. Always add matching string resources to `app/src/main/res/values/strings.xml`.

**Reading at runtime** — call `PrefManager.myPref` directly from any thread (the getter blocks briefly on DataStore). No injection needed; PrefManager is a singleton object.

## Application Entry Points

```
PluviaApp (Application, @HiltAndroidApp)
  └── MainActivity (singleTop ComponentActivity)
        └── PluviaMain.kt  ← root Compose function
```

`MainActivity` handles:
- Deep link `home://pluvia`
- Custom intents: `LAUNCH_GAME`, `OPEN_GAME_PAGE`

## Key Libraries

| Library | Version | Use |
|---|---|---|
| Compose BOM | 2025.01.01 | UI framework |
| Hilt | 2.55 | Dependency injection |
| Room | 2.8.4 | Local database |
| Kotlin Coroutines | 1.10.2 | Async |
| JavaSteam | 1.8.0.1-18-SNAPSHOT | Steam API |
| Coil | 2.4.6 | Image loading |
| PostHog | 3.8.0 | Analytics (consent-based) |
| OkHttp | 5.1.0 | HTTP |
| Navigation Compose | 2.8.6 | Screen navigation |
| DataStore | 1.1.2 | Preferences (`PrefManager.kt`) |
| Media3 | 1.9.1 | Video playback |
