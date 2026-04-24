# GameNative

## Project Overview

GameNative (`app.gamenative`) is an Android app that lets users play PC games from Steam, GOG, Epic Games, and Amazon Games on Android. It aggregates multi-platform game libraries, handles authentication and cloud saves per platform, and launches games through an embedded Windows emulation layer (Winlator/Box86/FEX). This is the user's fork of GameNative that aims to improve optimization for users with large game libraries (i.e., ~45k games on Steam), but may also include other fixes for issues the user decides to implement.

## Response guidelines

The user is an experienced developer but not intimately familiar with the Android SDK, Kotlin, or this codebase. Explain any plan, decision, or other response as if the user is a student looking to familiarize themselves with these topics. When writing code, always include comments around complex portions and that explain idioms in Android, Kotlin, or this codebase.

Your number one priority is to fix the issues the user presents in a performant manner. Database schema changes must only be made as an absolute last resort. It is intended that this personal fork maintain compatibility with upstream in the event that the user needs to revert. If you absolutely must make a schema migration, always confirm with the user first.

Try to keep diffs small where possible in the event that upstream provides fixes to issues the user is encountering and changes need to be reverted. It is also not necessary to apply comments to or clean up existing code unless it impacts performance and/or relates directly to a problem the user is trying to solve.

## Codebase Guide

### Gradle Modules

- `:app` — main application module
- `:ubuntufs` — dynamic feature module (Play Dynamic Delivery, downloads Ubuntu filesystem on demand)

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

## Build Variants

- `debug` — local development
- `release` — production (ProGuard enabled)
- `release-signed` — signed with Pluvia keystore
- `release-gold` — Gold tier release

**SDK**: minSdk 26 / targetSdk 28 / compileSdk 35  
**Version**: 0.9.0 (versionCode 14)

Secrets (PostHog key, SteamGridDB key, cloud project number) are injected at build time via Secrets Gradle Plugin — never hardcode them.

## Testing

- **Unit tests**: `app/src/test/` (~47 files) — service managers, DAOs, gamefixes registry, manifest parsing, crypto
- **Instrumented tests**: `app/src/androidTest/` (~5 files) — manifest parsing, crypto
- **Frameworks**: JUnit 4, Robolectric 4.14, Mockito 5.14.2, MockK 1.13.5, MockWebServer

## Localization

13 languages via `utils/LocaleHelper.kt`. Add new strings to all locale `strings.xml` files under `app/src/main/res/`.
