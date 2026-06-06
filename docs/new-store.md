# Adding a New Store / Storefront to GameNative

This document is an **exhaustive integration guide** for adding a *new* game storefront to
GameNative, alongside the existing ones (Steam, GOG, Epic, Amazon). It assumes the new store:

- has **no DRM**, and
- **downloads game files over plain HTTP** (no proprietary chunked/compressed manifest format).

It is written so that an implementer can wire up the new store **without re-exploring the
codebase**. Throughout, the new store is referred to by the placeholder name **`Foo`**:
enum value `GameSource.FOO`, entity `FooGame`, table `foo_games`, package `service/foo/`, etc.

> **Line numbers** in this doc are accurate as of writing but **will drift** as the code
> changes. Treat them as "look near here," not gospel. The reliable way to find every site
> that needs a new branch is to grep for `when (gameSource)`, `when (source)`, and
> `GameSource.AMAZON` — **Amazon is fully wired into every switch site**, so its branches are
> the canonical copy source.

---

## 0. TL;DR — the mental model

GameNative is a **multi-store aggregator**. Everything dispatches off a single central enum,
`GameSource` (`app/src/main/java/app/gamenative/data/LibraryItem.kt:7-14`):

```kotlin
enum class GameSource {
    STEAM,
    CUSTOM_GAME,
    GOG,
    EPIC,
    AMAZON
    // Add other platforms here..
}
```

Each store contributes four things:

1. **A Room entity + DAO** — its own table, registered in `PluviaDatabase` and `DatabaseModule`.
2. **A foreground `Service`** (`@AndroidEntryPoint`, `dataSync` type) — a thin companion-object
   facade that delegates to injected **Manager** singletons:
   `FooAuthManager` / `FooManager` / `FooDownloadManager` (+ optional `FooCloudSavesManager`).
3. **`PrefManager` keys** — auth tokens, game counts, a library-visibility toggle, sync dirs.
4. **UI plumbing** — a `LibraryTab` value, a `BaseAppScreen` subclass, an OAuth `Activity`, a
   login button, and a new branch in **~38 `when (gameSource)` switch sites** across UI/utils.

The unified library list is assembled in `LibraryViewModel.onFilterApps()`. Each store's entity
list is mapped into `LibraryItem`s whose `appId` is of the form `"<SOURCE>_<id>"` (e.g.
`"AMAZON_7"`), then everything is merged, sorted, and paginated into one list.

### Reference store: copy **Amazon**

For a no-DRM/HTTP store, **Amazon is the closest existing template**. Copy it:

- `AmazonDownloadManager.downloadGame` downloads files in parallel over **plain HTTP**, verified
  by **SHA256**, with **no zlib/chunk decompression**.
- Amazon is exhaustively wired into every `when (gameSource)` switch site, so copying its
  branches everywhere gives you full coverage.

GOG is a useful **secondary** reference for the auth pattern (OAuth2 code → token stored as a
JSON file in `filesDir`) and for cloud saves, if the new store ever needs them.

### What NOT to copy (store-specific complexity)

Avoid copying these — they are specific to their store and irrelevant to a no-DRM/HTTP store:

- GOG / Epic **chunk + zlib + MD5** manifest handling.
- Amazon's **protobuf `manifest.proto`** parsing (if your store uses a simple JSON/file list,
  skip this; keep only the plain-HTTP file download loop).
- Steam **PICS / depot** machinery (entirely Steam-specific).
- Epic `namespace == 'ue'` Unreal-asset filtering, DLC handling, family-sharing logic.

### Reuse, don't reinvent

- `data/DownloadInfo.kt` — progress/status object: `setProgress(Float)`, `setActive(Boolean)`,
  `updateStatusMessage(String?)`, `setPostInstallSyncing(Boolean)`.
- `utils/MarkerUtils.kt` — install-state marker files (`DOWNLOAD_IN_PROGRESS_MARKER`,
  `INSTALL_COMPLETE_MARKER`, etc.) that enable resume and partial-install cleanup.
- `utils/ContainerStorageManager.kt` / `utils/ContainerUtils.kt` — install-path resolution and
  Winlator container wiring. Both contain per-store switches you must extend.

> **Schema version note:** `CLAUDE.md` currently says the DB is "schema v19". That is **stale**.
> The actual current version is **23** (`db/PluviaDatabase.kt:58`). Adding a store table requires
> bumping to **24**. (Correcting `CLAUDE.md` is an optional separate follow-up.)

---

## 1. Data layer — entity, DAO, registration

### 1.1 Entity — `data/FooGame.kt`

Copy `app/src/main/java/app/gamenative/data/AmazonGame.kt` (`:1-117`) as the starting point.

**ID strategy** — pick one, based on the store's API:

- **String primary key** (like GOG's `id`): simplest if the store has stable string IDs.
- **Auto-generated Int PK + a separate "real" string id column** (like Amazon's auto-gen `appId`
  + `productId`, or Epic's auto-gen `id` + `catalogId`): use this if GameNative needs a numeric
  ID for container IDs / intents while the store's API keys on a string/UUID.

**Common required fields** (mirror Amazon/GOG):

| Field | Notes |
|---|---|
| `id` / `appId` | primary key (see strategy above) |
| `productId` / real store id | only if using auto-gen Int PK; index it |
| `title`, `developer`, `publisher`, `releaseDate` | metadata |
| `isInstalled: Boolean` | install state |
| `installPath: String` | where it's installed |
| `downloadSize: Long`, `installSize: Long` | sizes |
| `versionId: String` | for update checking |
| `artUrl` / image url(s) | at least one icon/cover URL |
| `lastPlayed: Long`, `playTime`/`playTimeMinutes: Long` | usage |
| `exclude: Boolean` (optional) | hide from library |

**Room rules to remember:**

- New non-null columns added in a later schema version **must** declare a default:
  `@ColumnInfo(defaultValue = "0")` (or `"''"`), otherwise auto-migration fails.
- If you store `List<String>` (genres, languages), reuse the existing `GOGConverter`
  (already registered in `PluviaDatabase`'s `@TypeConverters`). For a raw JSON blob, store it
  as a plain `String` column (Amazon does this with `productJson`).

**Credentials** live in a **separate `@Serializable` data class**, not in the entity — see
`AmazonCredentials` (`data/AmazonGame.kt:107-115`) or `GOGCredentials` (`data/GOGGame.kt:107-120`).
Typical fields: `accessToken`, `refreshToken`, `expiresAt`, and a user/account/device id.

### 1.2 DAO — `db/dao/FooGameDao.kt`

Copy `app/src/main/java/app/gamenative/db/dao/AmazonGameDao.kt`. **Minimal surface:**

```kotlin
@Dao
interface FooGameDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(games: List<FooGame>)

    @Query("SELECT * FROM foo_games WHERE id = :id")          // or :productId
    suspend fun getById(id: String): FooGame?

    @Query("SELECT * FROM foo_games ORDER BY title")
    fun getAll(): Flow<List<FooGame>>                          // reactive — drives LibraryViewModel

    @Query("SELECT * FROM foo_games ORDER BY title")
    suspend fun getAllAsList(): List<FooGame>

    @Query("SELECT * FROM foo_games WHERE isInstalled = 1")
    suspend fun getInstalledGames(): List<FooGame>

    @Query("SELECT * FROM foo_games WHERE isInstalled = 0")
    suspend fun getNonInstalledGames(): List<FooGame>

    @Query("SELECT * FROM foo_games WHERE title LIKE '%' || :q || '%' ORDER BY title")
    fun searchByTitle(q: String): Flow<List<FooGame>>

    @Query("SELECT COUNT(*) FROM foo_games")
    fun getCount(): Flow<Int>

    @Query("UPDATE foo_games SET isInstalled = 1, installPath = :path, installSize = :size, versionId = :versionId WHERE id = :id")
    suspend fun markAsInstalled(id: String, path: String, size: Long, versionId: String)

    @Query("UPDATE foo_games SET isInstalled = 0, installPath = '' WHERE id = :id")
    suspend fun markAsUninstalled(id: String)

    @Query("DELETE FROM foo_games WHERE isInstalled = 0")
    suspend fun deleteAllNonInstalledGames()

    @Transaction
    suspend fun upsertPreservingInstallStatus(games: List<FooGame>) {
        // Batch-fetch existing rows (avoid N+1), then merge incoming API data while
        // preserving local install state (isInstalled, installPath, installSize,
        // versionId, lastPlayed, playTime). See AmazonGameDao for the exact pattern.
    }
}
```

`upsertPreservingInstallStatus` is the **important one**: library sync re-fetches the whole
catalog from the store API, and you must not clobber the user's local install state. Copy
Amazon's implementation, which batch-fetches existing rows by id (no N+1 query per game).

### 1.3 Register the entity, DAO, and bump the schema version

In `app/src/main/java/app/gamenative/db/PluviaDatabase.kt`:

1. Import and add `FooGame::class` to the `@Database(entities = [...])` list (`:43-57`).
2. Add the accessor: `abstract fun fooGameDao(): FooGameDao` (near `:110-114`).
3. **Bump `version = 23` → `24`** (`:58`).
4. Add `AutoMigration(from = 23, to = 24)` to the `autoMigrations` list (`:81`). Adding a new
   table is a non-destructive auto-migration — no manual SQL needed.

In `app/src/main/java/app/gamenative/di/DatabaseModule.kt`, add a provider mirroring
`provideAmazonGameDao`:

```kotlin
@Provides
@Singleton
fun provideFooGameDao(db: PluviaDatabase): FooGameDao = db.fooGameDao()
```

On the next build, Room exports a new schema JSON at
`app/schemas/app.gamenative.db.PluviaDatabase/24.json` — **commit it**.

> ⚠️ **Per `CLAUDE.md`, confirm the schema version bump with the user before doing it.** Even
> though adding a table is non-destructive, schema changes are gated by project policy.

---

## 2. Service layer — `service/foo/`

Copy the shape of `app/src/main/java/app/gamenative/service/amazon/`. Create:

```
service/foo/
├── FooService.kt            (foreground Service, @AndroidEntryPoint, companion facade)
├── FooAuthManager.kt        (object — OAuth/credentials)
├── FooManager.kt            (@Singleton @Inject — library sync, DB CRUD)
├── FooDownloadManager.kt    (@Singleton @Inject — HTTP download)
├── FooConstants.kt          (object — URLs, client IDs, install paths)
└── api/
    ├── FooApiClient.kt      (OkHttp client for library + download endpoints)
    └── FooDataModels.kt     (response DTOs)
```

### 2.1 `FooService` (the facade)

- `class FooService : Service()`, annotated `@AndroidEntryPoint`.
- Companion-object singleton pattern (`instance` / `getInstance()`), like
  `AmazonService` / `GOGService`.
- Lifecycle: `onCreate()`, `onStartCommand()`, `onDestroy()`, `onTaskRemoved()`,
  `onBind()` → `null`. Call `startForeground()` via `NotificationHelper`. Return
  `START_STICKY`.
- `onStartCommand()` handles action intents: `ACTION_SYNC_LIBRARY` (auto, ~15-min throttle)
  and `ACTION_MANUAL_SYNC` (bypasses throttle), launching the sync in a coroutine that calls
  `fooManager.startBackgroundSync(...)`.
- **Companion API the rest of the app calls** — match the existing signatures so the UI
  switch sites stay uniform (compare to `AmazonService` / `GOGService`):

```kotlin
companion object {
    val isRunning: Boolean                                   // launch-deferral check
    fun start(context: Context)                              // start the service
    fun hasStoredCredentials(context: Context): Boolean      // gate library/tab visibility
    fun isGameInstalled(context: Context, id: String): Boolean
    fun getInstallPath(id: String): String                   // install dir — used by drive mapping + audio (Section 5)
    fun getFooGameOf(id: String): FooGame?                    // entity by container appId — used by the launch path
    fun getLaunchExecutable(/* containerId or appId */): String   // resolve .exe to launch (Section 5)
    suspend fun downloadGame(/* id, installPath, downloadInfo, ... */): Result<Unit>
    suspend fun deleteGame(context: Context, id: String): Result<Unit>
}
```

### 2.2 `FooAuthManager` (object)

Implements the store's OAuth and credential persistence. Two reference patterns:

- **Amazon** (`service/amazon/AmazonAuthManager.kt`): OIDC + PKCE (`AmazonPKCEGenerator`),
  device registration, tokens persisted via DataStore/`PrefManager`.
- **GOG** (`service/gog/GOGAuthManager.kt:24`): OAuth2 authorization-code flow, tokens
  serialized as JSON to a file in `filesDir` (`gog_auth.json`).

Pick whichever matches the store. Standard methods:

```kotlin
suspend fun authenticateWithCode(context, code): Result<FooCredentials>
fun hasStoredCredentials(context): Boolean
suspend fun getStoredCredentials(context): Result<FooCredentials>
suspend fun validateCredentials(context): Result<Boolean>
fun clearStoredCredentials(context): Boolean
```

### 2.3 `FooManager` (library sync)

`@Singleton class FooManager @Inject constructor(...)`. `startBackgroundSync(context)`:

1. `FooAuthManager.getStoredCredentials(context)`.
2. `FooApiClient.getLibrary(accessToken)` → list of DTOs (plain HTTP GET).
3. Map DTOs → `List<FooGame>`.
4. `fooGameDao.upsertPreservingInstallStatus(games)`.
5. Emit `AndroidEvent.LibraryUpdated` (or the equivalent library-changed event) so the UI
   refreshes. Update the `FOO_GAMES_COUNT` PrefManager key for skeleton loaders.

### 2.4 `FooDownloadManager` (the HTTP download)

`@Singleton class FooDownloadManager @Inject constructor(...)`. **Copy
`AmazonDownloadManager.downloadGame` (`service/amazon/AmazonDownloadManager.kt:44`)** — it is
the simplest, plain-HTTP path:

```kotlin
suspend fun downloadGame(
    context: Context,
    game: FooGame,
    installPath: String,
    downloadInfo: DownloadInfo,
): Result<Unit>
```

Flow:

1. `MarkerUtils.addMarker(installPath, DOWNLOAD_IN_PROGRESS_MARKER)`.
2. Fetch the download spec / file list over HTTP (`FooApiClient`). For a no-DRM store this is
   typically a JSON listing files + URLs + checksums.
3. Download files in parallel over plain HTTP. Verify each with the store's checksum (SHA256
   like Amazon). **No decompression** unless the store actually gzips individual files.
4. Assemble files into `installPath`, preserving directory structure.
5. Update `downloadInfo.setProgress(...)` / `updateStatusMessage(...)` as bytes arrive.
6. On success: `MarkerUtils.removeMarker(... DOWNLOAD_IN_PROGRESS_MARKER)`,
   `MarkerUtils.addMarker(... INSTALL_COMPLETE_MARKER)`, and `fooGameDao.markAsInstalled(...)`.

**Install path:** define `FooConstants.internalFooGamesPath` / `externalFooGamesPath` following
`AmazonConstants` (base path + sanitized game title). `ContainerStorageManager` (switch sites in
Section 6) also needs to know about these paths.

### 2.5 Launch

`getLaunchExecutable` resolves the Windows `.exe` to run — either by scanning the install
directory for executables (GOG/Amazon style, via `ExecutableSelectionUtils`) or by reading an
`executable` field stored on the entity during download (Epic style). It returns a path string
that the Winlator container launches. **The full launch/XServer wiring — drive mapping, the Wine
command builder, container persistence, and the Steam/Amazon/Epic-only machinery you must avoid
— is its own topic; see Section 5.**

### 2.6 AndroidManifest registration

In `app/src/main/AndroidManifest.xml` (existing store entries at `:92-157`):

```xml
<service
    android:name=".service.foo.FooService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />

<activity
    android:name=".ui.screen.auth.FooOAuthActivity"
    android:exported="false" />
```

(The `dataSync` foreground type requires the already-declared
`FOREGROUND_SERVICE_DATA_SYNC` permission.)

---

## 3. PrefManager keys (`PrefManager.kt`)

Add, mirroring the existing per-store key patterns:

- **Auth tokens** (only if not file-based) — encrypted byte-array keys following the Steam
  pattern (`:876-906`). GOG-style file storage needs none of these.
- **Game counts** for skeleton loaders (`:1169-1209`):
  ```kotlin
  private val FOO_GAMES_COUNT = intPreferencesKey("foo_games_count")
  private val FOO_INSTALLED_GAMES_COUNT = intPreferencesKey("foo_installed_games_count")
  ```
- **Library-visibility toggle** (`:1133-1152`):
  ```kotlin
  private val SHOW_FOO_IN_LIBRARY = booleanPreferencesKey("show_foo_in_library")
  var showFooInLibrary: Boolean
      get() = getPref(SHOW_FOO_IN_LIBRARY, true)
      set(value) { setPref(SHOW_FOO_IN_LIBRARY, value) }
  ```
- **Frontend sync dir** (`:1280-1324`):
  ```kotlin
  private val FRONTEND_SYNC_DIR_FOO = stringPreferencesKey("frontend_sync_dir_foo")
  ```
  …then add a `GameSource.FOO` case to **both** `getFrontendSyncDir(source)` (`:1307`) and
  `setFrontendSyncDir(source, path)` (`:1317`).
- **Optional** `FOO_OFFLINE_MODE` (`:617-629` pattern) if the store supports offline launch.

---

## 4. UI layer

### 4.1 OAuth Activity

Create `app/src/main/java/app/gamenative/ui/screen/auth/FooOAuthActivity.kt`, copying
`AmazonOAuthActivity.kt` (or `GOGOAuthActivity.kt` for a plain code flow). These are WebView
activities that intercept the OAuth redirect and hand the auth code back via an `AndroidEvent`
(or activity result). Register it in the manifest (done in Section 2.6).

### 4.2 Login UI

- `ui/screen/login/UserLoginScreen.kt`: add a launcher + result handler (copy the Amazon block
  ~`:233-270`) and a "Foo" login button (~`:552-575`).
- `ui/screen/settings/SettingsGroupInterface.kt`: add the login/logout state + OAuth event
  listener (`:190-253`, GOG block as reference).
- If the store uses the auth-code event-bus pattern, add
  `data class FooAuthCodeReceived(val authCode: String)` to
  `events/AndroidEvent.kt` (`:34-35`).

### 4.3 Library tab & state

- `ui/enums/LibraryTab.kt` (`:6-83`): add a `FOO` enum value and a `showFoo` flag column; set
  `showFoo = true` for the `ALL` and `FOO` tabs.
- `ui/data/LibraryState.kt` (`:27-71`): add `val showFooInLibrary: Boolean` and `val fooCount: Int`.

### 4.4 Game detail screen

- Create `ui/screen/library/appscreen/FooAppScreen.kt` extending `BaseAppScreen` — copy
  `AmazonAppScreen.kt`.
- Add the routing case in `ui/screen/library/LibraryAppScreen.kt` (`:651`):
  ```kotlin
  GameSource.FOO -> FooAppScreen()
  ```

### 4.5 LibraryViewModel wiring (`ui/model/LibraryViewModel.kt`)

This is where store lists merge. Mirror Amazon:

1. In `init {}`, subscribe to the DAO and re-filter on change:
   ```kotlin
   viewModelScope.launch(Dispatchers.IO) {
       fooGameDao.getAll().collect { games ->
           fooGameList = games
           onFilterApps(paginationCurrentPage)
       }
   }
   ```
2. `onSourceToggle(GameSource)` (`:323`): add a `GameSource.FOO` branch toggling
   `PrefManager.showFooInLibrary`.
3. In `onFilterApps()`, add a Foo mapping block (copy the Amazon block ~`:846-859`):
   ```kotlin
   val fooEntries = filteredFooGames.map { game ->
       LibraryItem(
           appId = "${GameSource.FOO.name}_${game.id}",
           name = game.title,
           iconHash = game.artUrl,
           gameSource = GameSource.FOO,
           isInstalled = game.isInstalled,
           sizeBytes = game.downloadSize,
           // ...
       )
   }
   ```
4. In the merge step (`:883-918`), add:
   ```kotlin
   val includeFoo = (if (currentTab == ALL) showFooInLibrary else currentTab.showFoo) &&
                    FooService.hasStoredCredentials(context)
   // ...
   if (includeFoo) addAll(fooEntries)
   ```

### 4.6 LibraryScreen

- Login splash for an empty/logged-out tab (`LibraryScreen.kt:916`):
  ```kotlin
  LibraryTab.FOO -> !FooService.hasStoredCredentials(context)
  ```
- Install dispatch (`LibraryScreen.kt:1317`) and uninstall dispatch
  (`performLibraryUninstall`, `:1316-1362`): add `GameSource.FOO` branches calling
  `FooService.downloadGame(...)` and `FooService.deleteGame(...)`.

### 4.7 Strings & drawables

- `app/src/main/res/values/strings.xml`: add `tab_foo`, `library_source_foo`,
  `library_source_not_logged_in_foo`, `foo_install_game_title`, `foo_uninstall_game_title`,
  login success/cancel strings, etc. (existing entries at `:109-112`, `:1334-1339`).
- `app/src/main/res/drawable/ic_foo.png`: store logo, referenced wherever store badges render
  (e.g. `LibraryAppItem.kt:339`).

---

## 5. Launching games — the XServer / Winlator path

Installing a game is only half the job; the **launch path** (the `com.winlator` emulation layer)
also has store-specific wiring. For a no-DRM/HTTP store this is mostly "copy Amazon and delete
the DRM bits," but **one of these sites is NOT compiler-protected**, so read this carefully.

### 5.1 How a launch works (persisted-container model)

- A Winlator **`Container` is created once per game and persisted to disk.** On later launches the
  existing container is loaded; it is **not** recreated. `ContainerUtils.getOrCreateContainer`
  (`utils/ContainerUtils.kt:985`) re-validates and, if needed, updates the drive mapping on
  **every** launch.
- **Install dir → `A:` drive.** All non-Steam stores mount the game's install directory as the
  `A:` drive (Steam alone uses a dedicated lettered drive). The mapping is built in
  `ContainerUtils.createNewContainer` (`:642-730`) — an **exhaustive `when (gameSource)`**, so the
  compiler *will* force you to add a `GameSource.FOO` branch. Copy the GOG/Epic/Amazon branch:
  fetch the install path (`FooService.getInstallPath(...)` / `getFooGameOf(id).installPath`) and
  map it to `A:`.
- **Exe caching.** Every store caches the resolved executable in `container.executablePath` after
  the first launch to avoid re-scanning the install tree. Your store should do the same and call
  `container.saveData()`.
- **Container IDs need no new plumbing.** Container IDs reuse the `"<SOURCE>_<id>"` `appId`
  convention; `extractGameSourceFromContainerId` / `extractGameIdFromContainerId`
  (`ContainerUtils.kt`) already understand any new prefix automatically.

### 5.2 The Wine launch-command builder — ⚠️ the one that bites

`XServerScreen.getWineStartCommand()` (`ui/screen/xserver/XServerScreen.kt:4024`) builds the
actual Windows command line that Wine runs. **It is an `if / else-if` chain on `gameSource`, NOT
an exhaustive `when`** (`isGOGGame` at `:4053`, `gameSource == GameSource.AMAZON` at `:4187`).

> ⚠️ **This means the Kotlin compiler will NOT flag a missing branch.** If you forget to add a
> `GameSource.FOO` case here, the game silently falls through to the **Steam** launch path and
> fails at runtime in a confusing way (it'll try to launch via steamclient loader). This is the
> single most important manual step in the launch path — there is no compile-time safety net.

For a no-DRM/HTTP store the branch is simple. **Copy the Amazon branch (`:4187`) and strip the
DRM bits** (FuelPump env vars, SDK deployment, fuel.json is optional). Resolve the exe + the `A:`
drive and return something like:

```kotlin
// inside getWineStartCommand(), add before the final fallthrough:
} else if (gameSource == GameSource.FOO) {
    // resolve relative exe path under the A: drive (install dir), set working dir, then:
    return "winhandler.exe \"A:\\${relativeExePath}\"" // + optional space-quoted args
}
```

If your store ships a launch manifest (analogous to Amazon's `fuel.json` or GOG's
`goggame-*.info`) you can parse it here for the command/args/working-subdir; otherwise a
first/largest-`.exe` heuristic via `ExecutableSelectionUtils.choosePrimaryExeFromDisk()` is fine.

### 5.3 Extra service methods the launch path needs

Beyond `getLaunchExecutable`, the launch path calls:

- `FooService.getInstallPath(id): String` — used by drive mapping (5.1) and by **XAudio DLL
  injection** (`XAudioUtils.kt:30`, an exhaustive `when` — compiler-checked). Mirror
  `GOGService.getInstallPath` / `AmazonService.getInstallPath`.
- `FooService.getFooGameOf(id): FooGame?` — entity lookup by container appId, for the above.
- `getLaunchExecutable(...)` — resolves + caches the exe in `container.executablePath`.

### 5.4 Steam/Amazon/Epic-only machinery to AVOID

A no-DRM/HTTP store needs **none** of the following. Do **not** copy them when adapting branches:

- **Steam fake client:** `SteamClientComponent` is added to the XEnvironment only for
  non-Bionic/non-Real Steam games (`XServerScreen.kt:~3512`). Your store should not add it.
- **Steam DRM/loaders:** ColdClientLoader, `SteamUtils.writeColdClientIni`, Bionic-Steam / Real-
  Steam modes, and the `SteamBootstrap.stop()` exit cleanup (`XServerScreen.kt:~4529`) are all
  Steam-only. (Non-Steam stores have **no** special exit handling.)
- **Amazon FuelPump:** the `FUEL_DIR` / `AMAZON_GAMES_SDK_PATH` / `AMAZON_GAMES_FUEL_*` env vars
  and `AmazonSdkManager` SDK deployment (`XServerScreen.kt:~4278-4336`) are Amazon DRM — skip.
- **Epic auth:** `-AUTH_*` / `-epic*` launch params and the ownership token
  (`EpicGameLauncher` / `EpicService.buildLaunchParameters`) are Epic-only — skip.
- **LaunchDependencies:** you need **no** store-specific entry in `utils/LaunchDependencies.kt`;
  the generic `BionicDefaultProtonDependency` already provides Wine/Proton for every store.

### 5.5 Things that "just work" generically

These are store-agnostic — no `GameSource.FOO` branch needed: general Wine/Proton env vars,
game fixes (register fixes under the `FOO_` prefix in `gamefixes/`), pre-install steps, patch
chaining, and session analytics. `supportsKnownConfigAutoApply` (`ContainerUtils.kt:1174`) should
return `true` for your store (so Wine/Proton manifest auto-install runs, like the other stores).

---

## 6. The `when (gameSource)` switch-site checklist

Kotlin's `when` over an enum is **exhaustive** — adding `FOO` to `GameSource` will cause a
**compile error at every `when (gameSource)` that lacks an `else`**. That is your main safety net
(see Section 9). **The big exception is `XServerScreen.getWineStartCommand()` (Section 5.2), which
is an `if/else` chain and will NOT fail to compile if you miss it.** Below is the full known set as
of writing. **For each, copy the `GameSource.AMAZON` branch** and adapt it.

| Area | File | Line(s) | Purpose |
|---|---|---|---|
| Data | `data/LibraryItem.kt` | 50 | `clientIconUrl` — icon URL resolution |
| Data | `data/LibraryItem.kt` | 85 | parse numeric `gameId` from prefixed `appId` |
| API | `api/PatchApi.kt` | 50 | store prefix for patch API |
| Prefs | `PrefManager.kt` | 1307 | `getFrontendSyncDir(source)` |
| Prefs | `PrefManager.kt` | 1317 | `setFrontendSyncDir(source, path)` |
| Sync | `sync/FrontendSyncManager.kt` | 108 | `extensionFor(source)` |
| Sync | `sync/FrontendSyncManager.kt` | 192 | games-to-sync list per store |
| Sync | `sync/FrontendSyncManager.kt` | 227 | `lookupGameName(appId, source)` |
| Sync | `sync/FrontendSyncManager.kt` | 234 | `isGameInstalled(appId, source)` |
| Game fixes | `gamefixes/GameFixesRegistry.kt` | 57 | `catalogId` lookup |
| Game fixes | `gamefixes/GameFixesRegistry.kt` | 72 | fix application per store |
| UI model | `ui/model/DownloadsViewModel.kt` | 211, 356, 613, 641, 738 | status label, progress %, verify, delete |
| UI model | `ui/model/GamePageViewModel.kt` | 156 | store-specific game data mapping |
| UI model | `ui/model/MainViewModel.kt` | 529 | hero/logo/name URLs |
| UI model | `ui/model/LibraryViewModel.kt` | 323 | `onSourceToggle` visibility toggle |
| Main | `ui/PluviaMain.kt` | 196 | `isInstalled` check |
| Main | `ui/PluviaMain.kt` | 248 | launch-deferral check (`isRunning`) |
| Main | `ui/PluviaMain.kt` | 1884 | resolve launch executable |
| Main | `ui/PluviaMain.kt` | 2149 | store-native id mapping (telemetry) |
| Screen | `ui/screen/downloads/DownloadsScreen.kt` | 1035 | source display label |
| Screen | `ui/screen/downloads/DownloadsScreen.kt` | 1055 | container color |
| Screen | `ui/screen/downloads/DownloadsScreen.kt` | 1064 | content color |
| Screen | `ui/screen/library/LibraryAppScreen.kt` | 651 | **AppScreen routing** (critical) |
| Screen | `ui/screen/library/LibraryScreen.kt` | 916 | login-splash gate |
| Screen | `ui/screen/library/LibraryScreen.kt` | 1317 | install-button dispatch |
| Screen | `ui/screen/library/LibraryScreen.kt` | 1316 | `performLibraryUninstall` dispatch |
| Screen | `ui/screen/library/appscreen/BaseAppScreen.kt` | 1016, 1276 | download info / container color |
| XServer | `ui/screen/xserver/XServerScreen.kt` | 4024 | **Wine launch-command builder — ⚠️ if/else chain, NOT compiler-checked (Section 5.2)** |
| Component | `ui/screen/library/components/LibraryAppItem.kt` | 339 | grid card store badge |
| Component | `ui/screen/library/.../LibraryGridCard.kt` | 507 | grid card styling |
| Settings | `ui/screen/settings/ContainerStorageManagerDialog.kt` | 926 | storage location label |
| XServer | `ui/screen/xserver/XAudioUtils.kt` | 30 | audio config per store |
| Utils | `utils/ContainerStorageManager.kt` | 330, 416, 805, 833, 846, 877, 1009 | container root path, migration, installed list, storage roots, partial-install cleanup |
| Utils | `utils/ContainerUtils.kt` | 642, 985, 1174, 1192 | drive-letter map, game folder path, config auto-apply, custom launch command |
| Utils | `utils/GameFeedbackUtils.kt` | 38 | game name for bug reports |

> To regenerate this list against live code:
> `grep -rn "when (gameSource)\|when (source)\|GameSource\.AMAZON" app/src/main/java`

---

## 7. Events

New stores generally **reuse the generic `AndroidEvent`** system rather than defining a
dedicated event interface (Steam is the exception — it has its own `SteamEvent` for its complex
connection lifecycle). Relevant generic events:

- `AndroidEvent.LibraryInstallStatusChanged(appId: Int, source: GameSource)` — emit after
  install/uninstall so the library/downloads UI updates.
- `AndroidEvent.FooAuthCodeReceived(authCode: String)` — if using the WebView OAuth event bus.
- A library-updated event after sync so `LibraryViewModel` refreshes.

Events are dispatched via the `PluviaApp.events` singleton (`EventDispatcher`).

---

## 8. Ordered implementation checklist

1. Add `FOO` to `GameSource` (`data/LibraryItem.kt`). *(This immediately breaks compilation at
   every exhaustive `when` — that's expected and useful.)*
2. Create `data/FooGame.kt` (entity) and `db/dao/FooGameDao.kt`.
3. Register entity + DAO in `PluviaDatabase`; **bump version 23 → 24** + add the auto-migration
   *(confirm with the user first)*.
4. Add the DAO provider in `di/DatabaseModule.kt`.
5. Create the `service/foo/` package (Service + Auth/Manager/Download managers + Constants + api).
   Include the launch helpers: `getInstallPath`, `getFooGameOf`, `getLaunchExecutable`.
6. Add the `<service>` and OAuth `<activity>` to `AndroidManifest.xml`.
7. Add `PrefManager` keys (counts, visibility toggle, sync dir, optional tokens/offline).
8. Create `ui/screen/auth/FooOAuthActivity.kt` + login UI in `UserLoginScreen` / settings.
9. Add the `LibraryTab.FOO` value + `LibraryState` fields.
10. Create `FooAppScreen` and add the routing case in `LibraryAppScreen`.
11. Wire `LibraryViewModel` (DAO subscription, `onSourceToggle`, mapping block, merge gate).
12. Fix **every** `when (gameSource)` switch site (Section 6) — copy the Amazon branch.
13. **Wire the launch path (Section 5):** add the `A:`-drive branch in
    `ContainerUtils.createNewContainer`, add the `getInstallPath` case in `XAudioUtils`, and — the
    one with **no compile-time safety net** — add the `GameSource.FOO` branch to
    `XServerScreen.getWineStartCommand()`. Do **not** copy the Steam/Amazon/Epic DRM machinery.
14. Add string resources and the store drawable.
15. Build, fix any remaining exhaustiveness errors, and verify (Section 9).

---

## 9. Verification

- **Compile:** `gradlew.bat :app:assembleDebug` (PowerShell) / `./gradlew :app:assembleDebug`.
  - **Room KSP** fails loudly if the entity/DAO/version/migration are inconsistent.
  - **Kotlin `when` exhaustiveness** fails compilation for any switch site still missing the
    `GameSource.FOO` branch — this is the **primary "did I miss a switch?" check**. Keep
    compiling until it's clean. ⚠️ **It does NOT cover `XServerScreen.getWineStartCommand()`**
    (Section 5.2) — that if/else site must be verified by hand/at runtime.
- Confirm a new schema file was generated at
  `app/schemas/app.gamenative.db.PluviaDatabase/24.json` and commit it.
- **Manual end-to-end:**
  1. Log into the store via the OAuth activity → credentials persist.
  2. Library syncs and Foo games appear under the `ALL` and `FOO` tabs (gated on
     `hasStoredCredentials`).
  3. Install a game → files download over HTTP to the expected `FooConstants` path, markers are
     written, `DownloadInfo` progress advances, and the entity flips to `isInstalled`.
  4. Launch the game → it runs through the **new `getWineStartCommand` branch** (NOT the Steam
     fallthrough — confirm via logs/behavior), `A:` maps to the install dir, and
     `getLaunchExecutable` resolves the `.exe`. Re-launch to confirm the `container.executablePath`
     cache works. Verify **no** Steam/Amazon/Epic DRM env vars or `SteamClientComponent` are
     present.
  5. Uninstall → files removed, entity flips back, library/downloads UI updates.
