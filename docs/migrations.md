# Schema Migration Policy for This Fork

This fork increments the Room database version independently of upstream GameNative. This document covers the two situations where that divergence creates a problem.

---

## Situation 1 — Rolling back to upstream (upstream is at v20, fork is at v21)

**What happens without intervention:**
Room reads the on-device database version (v21) and compares it to the version declared in `PluviaDatabase.kt` (v20 in upstream). Because the on-device version is *higher* than the code version, Room has no migration path defined and triggers the destructive fallback (`fallbackToDestructiveMigration(true)` in `DatabaseModule.kt`). This **wipes the entire database** — all tables: `steam_app`, `steam_license`, `cached_license`, `change_numbers`, `file_change_lists`, `gog_games`, `epic_games`, `amazon_games`, `app_info`, `encrypted_app_ticket`, `steam_unlocked_branch`, and `downloading_app_info`. Everything repopulates on next login except `downloading_app_info` — any in-progress downloads lose their queue state and must be restarted. Because `change_numbers` is also wiped, Steam performs a full PICS sync from scratch (no prior changelog position), which takes roughly 10–20 minutes for a library of ~45k games. Cloud saves are unaffected (stored on Steam's servers).

**What to do:**
Nothing extra is required. Simply install upstream's build and log in. PICS re-syncs the library. The wipe is the intended safe-fallback behavior.

If you want to avoid the wipe (e.g., to preserve a large library cache): before switching builds, manually clear app data in Android Settings → Apps → GameNative → Storage → Clear Data. This resets the database to a fresh state at whatever version the newly installed build expects, avoiding the version mismatch entirely.

---

## Situation 2 — Upstream releases its own v21 while this fork is already at v21

**What happens without intervention:**
Both schemas are at v21 but describe different changes. If you merge (or rebase onto) upstream's v21 without renumbering, the `AutoMigration(from = 20, to = 21)` entry in `PluviaDatabase.kt` becomes ambiguous — the schema JSON file `app/schemas/.../21.json` will reflect whichever changes are currently in the entity classes, which may not match Room's expectations. Room's annotation processor will emit a compile-time error about a schema mismatch.

> **Rebase vs. merge:** conflict markers are inverted during a rebase. `HEAD` is the upstream branch you're rebasing onto; the named commit is your fork's work. Check the commit message in the conflict marker to identify which side is which before deciding what to keep.

**What to fix — the safe approach (no wipe for existing users):**

This fork encountered this exact situation at v21. Upstream's v21 added `steam_file_hash_cache`; this fork's v21 added `content_descriptors` to `steam_app`. The resolution — now committed — uses manual migrations to handle all device states without a destructive wipe.

**Step 1 — Renumber the fork to v22** in `PluviaDatabase.kt`:
- Change `version = 21` → `version = 22`
- Remove `AutoMigration(from = 20, to = 21)` entirely (see step 2 for why)
- Do **not** add any AutoMigration for v21→v22 — the manual migrations cover both gaps

**Step 2 — Delete the conflicted schema JSON and go all-manual.**
Delete `app/schemas/.../21.json`. It has unresolvable conflict markers, and reconstructing it requires knowing the exact identity hash Room would compute for the intermediate schema — not practical.

> **Important:** Room's KSP processor needs `N.json` to exist before it will generate `AutoMigration(from=N-1, to=N)`. Deleting `21.json` means `AutoMigration(from=20, to=21)` can no longer be used — that's why it must also be removed from the `autoMigrations` list. With no AutoMigration entries referencing v21, Room never looks for `21.json`. It only needs `20.json` (which exists) and generates `22.json` from the current entity classes.

**Step 3 — Write two manual migrations** in `RoomMigration.kt`, covering all three starting states:

| Came from | Has `content_descriptors`? | Has `steam_file_hash_cache`? | Migration |
|---|---|---|---|
| v20 (ran neither v21) | No | No | `Migration(20, 22)` |
| Upstream v21 | No | Yes | `Migration(21, 22)` |
| Fork's v21 | Yes | No | `Migration(21, 22)` |

`Migration(20, 22)` — no defensive checks needed, v20 has neither change:
```kotlin
connection.execSQL("CREATE TABLE IF NOT EXISTS `steam_file_hash_cache` (...)")
connection.execSQL("ALTER TABLE steam_app ADD COLUMN content_descriptors TEXT NOT NULL DEFAULT '[]'")
```

`Migration(21, 22)` — each v21 is missing exactly one change, so both operations are defensive:
- Use `PRAGMA table_info(steam_app)` to detect whether `content_descriptors` already exists, then `ALTER TABLE ADD COLUMN` only if absent. SQLite has no `ADD COLUMN IF NOT EXISTS`.
- Use `CREATE TABLE IF NOT EXISTS` for `steam_file_hash_cache` — natively idempotent.

See `ROOM_MIGRATION_V20_to_V22` and `ROOM_MIGRATION_V21_to_V22` in `RoomMigration.kt` for the full implementation.

**Step 4 — Register both migrations** in `DatabaseModule.kt`:
```kotlin
.addMigrations(ROOM_MIGRATION_V7_to_V8, ROOM_MIGRATION_V20_to_V22, ROOM_MIGRATION_V21_to_V22)
```

**Upgrade paths after this fix:**
- *Device on v20:* `Migration(20, 22)` creates `steam_file_hash_cache` and adds `content_descriptors`. Clean.
- *Device on upstream v21:* `Migration(21, 22)` adds `content_descriptors` (PRAGMA confirms absent) and no-ops `CREATE TABLE IF NOT EXISTS`. Clean.
- *Device on fork's v21:* `Migration(21, 22)` skips `ADD COLUMN` (PRAGMA confirms present) and creates `steam_file_hash_cache`. Clean. **No wipe.**

**What to fix — the simple approach (accepts a one-time wipe):**

If you don't mind existing fork users losing their library cache and waiting 10–20 minutes for a full PICS re-sync on first login, you can skip the manual migrations entirely:

1. Bump to `version = 22`. Do not add any AutoMigration referencing v21 (21.json is gone). Add a single `Migration(20, 22)` and `Migration(21, 22)` that apply all changes non-defensively.

Or, if you kept `21.json`, you could add both `AutoMigration(from=20, to=21)` and `AutoMigration(from=21, to=22)` — but devices on the fork's v21 will hit `duplicate column name`, trigger `fallbackToDestructiveMigration(true)`, and wipe all tables.

**Going forward:** watch upstream's schema version in `PluviaDatabase.kt` before merging or rebasing. If upstream bumps from v21 to v22, this fork would need to go to v23. The manual migration pattern only needs to be repeated if another same-version collision occurs — if you stay one version ahead proactively, future bumps are plain `AutoMigration` entries (provided the intermediate JSON file is intact).
