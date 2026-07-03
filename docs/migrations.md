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

> **Superseded:** the fold strategy above (`ROOM_MIGRATION_V20_to_V22` / `V21_to_V23` and their siblings) was **removed** when the fork was later rebased onto master. That rebase adopted "Approach A" instead — a clean, linear `AutoMigration` chain with one new version per fork change. The `ROOM_MIGRATION_V20_to_V2x` migrations this section references no longer exist. See **Situation 3** for what replaced them and the failure modes it exposed.

---

## Situation 3 — Rebasing the fork onto upstream master (the v23–v29 collisions)

When the fork was rebased onto master, both branches had independently bumped the DB version, colliding at **v23 through v28**. This section records the approach taken and — more importantly — the runtime failures that divergent version numbering caused on devices that had run the pre-rebase fork.

### Approach A (chosen over the fold)

Keep master's linear `AutoMigration` chain intact and give **every** fork schema change its own **new** version appended to the end, rather than folding changes into manual migrations:

| Version | Change |
|---|---|
| 24 | `content_descriptors` on `steam_app` |
| 25 | `size_bytes` on `steam_app` |
| 26 | `name_sort_key` + `is_adult` on `steam_app` (+ `steam_app` indices) |
| 27 | `store_tags` on `steam_app` + `steam_tag` table |
| 28 | `steam_curator` + `steam_curator_recommendation` tables |
| 29 | `review_url` on `steam_curator_recommendation` |

Each is a plain `AutoMigration(from = N-1, to = N)` generated from the entity classes (schema JSON `N.json` regenerated by the KSP build) — with the two exceptions noted below.

**Indices belong in `@Entity(indices = [...])`, not a `RoomDatabase.Callback.onOpen` `CREATE INDEX`.** The fork originally created `steam_app` indexes in `onOpen`. Those indexes are *not* part of the exported schema, so once they exist on a device, the **next** auto-migration fails runtime validation because the "found" table has indexes the "expected" schema doesn't. Declaring them in `@Entity` folds them into the schema and keeps every auto-migration valid. The `onOpen` callback was removed.

### The core hazard: divergent version numbers break in-place upgrades for pre-rebase devices

A device that ran the **pre-rebase fork** build is at integer version **28**, but the fork's v28 schema is *not the same* as our v28 schema. When such a device upgrades to our v29, Room runs **only the `28→29` migration** — it considers 20–28 already applied — so any schema object we assume was created back at v20–v28 is missing or different on that device.

This produced three consecutive startup crashes, all the same root cause:

1. **`duplicate column name: review_url`** — the fork already added `review_url` at *its* v28, so our unguarded `AutoMigration(28→29)` tried to add it again.
2. **`Migration didn't properly handle: library_play_history`** — a master table (added at master's v23) that the fork forked before and never had; the fork device skipped the `22→23` that creates it.
3. **`review_url` default mismatch — `Expected '' / Found undefined`** — the fork's *fresh install* created `review_url TEXT NOT NULL` with no SQL default, but our entity declared `defaultValue = ""`, so Room strictly required `''`.

### The fix pattern: the boundary migration reconciles the whole fork schema

The single migration a pre-rebase device runs (`28→29`) must bring the fork's *final* schema all the way up to our target schema — **idempotently**, so it's a no-op on master-origin devices (which already have everything). Convert that boundary step to a **manual** `Migration` and, for each delta, use a form that is safe to run twice:

- **columns:** `hasColumn(table, col)` (a `PRAGMA table_info` scan) guard before `ALTER TABLE … ADD COLUMN`.
- **tables:** `CREATE TABLE IF NOT EXISTS` — copy the exact DDL from the target `N.json` `createSql`.
- **indices:** `CREATE INDEX IF NOT EXISTS index_<…>` for the expected `@Entity` indices, **plus** `DROP INDEX IF EXISTS idx_<…>` to remove the fork's old `onOpen` indexes.

See `ROOM_MIGRATION_V28_to_V29` in `RoomMigration.kt` for the full implementation (it reconciles `review_url`, `library_play_history`, and the `steam_app` indices in one step).

### Room gotchas confirmed the hard way

- **Room enforces a column's default value only when the *entity* declares one.** For a column added by a manual migration, do **not** put `@ColumnInfo(defaultValue = …)` on the entity — put the `DEFAULT` in the migration SQL only. Room then skips the default check for that column, so both `''` and `undefined` on-device states validate. (`defaultValue` on the entity is required *only* to make an **AutoMigration**-added NOT NULL column compile — which is exactly why it was mistakenly added and then had to be removed.)
- **`fallbackToDestructiveMigration(true)` does not rescue a schema-validation failure** that happens *after* a migration runs. It only triggers when a migration path is missing. A `Migration didn't properly handle:` is a hard crash, not a wipe.

### Diagnose the full delta up front (avoid whack-a-mole)

Before writing the reconciliation migration, enumerate **all** differences between the fork's final schema and ours — tables, columns (name / notNull / affinity / defaultValue), indices, and foreign keys — by diffing the exported schema JSON: `git show <fork-ref>:app/schemas/.../<N>.json` vs `HEAD:app/schemas/.../<M>.json`. Fixing one crash at a time just surfaces the next.

### Verification caveats

- **A green build does not prove migrations are correct.** Schema validation is a **runtime** check; test on a device (or emulator) that actually holds the *old* fork schema, not a fresh install.
- **KSP-only Gradle tasks don't fully typecheck Kotlin.** Use `:app:compileLegacyDebugKotlin` to catch dangling references introduced by auto-merges. Note that some intermediate feature-branch commits legitimately don't self-compile (they reference a symbol a later commit adds) — validate at the branch **tip**.

### Editing a historical commit non-interactively

This repo blocks `git rebase -i`. To fold a fix into the commit that introduced a problem: `git checkout <sha>` → make the edit → `git commit --amend --no-edit` → `git rebase --onto <new-sha> <old-sha> <branch>`. This is safe as long as the commits after `<old-sha>` don't touch the same files (confirm with `git log <old-sha>..<branch> -- <files>`).
