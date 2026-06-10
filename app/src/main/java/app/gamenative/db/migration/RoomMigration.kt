package app.gamenative.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

private const val DROP_TABLE = "DROP TABLE IF EXISTS " // Trailing Space

// SQLite has no ADD COLUMN IF NOT EXISTS — every defensive migration below checks via PRAGMA first.
private fun SQLiteConnection.hasColumn(table: String, column: String): Boolean {
    var found = false
    prepare("PRAGMA table_info($table)").use { stmt ->
        while (stmt.step()) {
            // Index 1 is the column name in PRAGMA table_info output.
            if (stmt.getText(1) == column) {
                found = true
                break
            }
        }
    }
    return found
}

// Devices already past the v20/v21 jump (real old-numbering v22 through v27) never ran either jump
// migration, so they never got vertical_cover_url — it's purely a master-merged column, never part
// of any pre-rebase fork build. Every step from v23→v24 through v27→v28 calls this so the column
// lands exactly once on the way to v28, regardless of which real version the device starts at.
private fun SQLiteConnection.addVerticalCoverUrlIfMissing() {
    if (!hasColumn("gog_games", "vertical_cover_url")) {
        execSQL("ALTER TABLE gog_games ADD COLUMN vertical_cover_url TEXT NOT NULL DEFAULT ''")
    }
}

internal val ROOM_MIGRATION_V7_to_V8 = object : Migration(7, 8) {
    override fun migrate(connection: SQLiteConnection) {
        // Dec 5, 2025: Friends and Chat features removed
        connection.execSQL(DROP_TABLE + "chat_message")
        connection.execSQL(DROP_TABLE + "emoticon")
        connection.execSQL(DROP_TABLE + "steam_friend")
    }
}

// v21/v22 schema collision: upstream shipped v21 (adds steam_file_hash_cache) and later its own
// v22 (adds GOG vertical_cover_url), while this fork had already shipped its own v21 (adds
// content_descriptors) and renumbered to v22 to resolve the first collision. Master's v22 then
// collided with the fork's already-shipped v22 a second time. Rather than chase the version
// number further with AutoMigrations (which would require a path "from=22" that real fork-v22
// devices would hit and crash on a duplicate-column error instead of safely falling back), the
// fork jumps straight to v23, skipping 21 and 22 entirely:
//
//   - Device on v20 (ran none of the three changes): ROOM_MIGRATION_V20_to_V23 applies all three.
//   - Device on upstream v21 (steam_file_hash_cache only): ROOM_MIGRATION_V21_to_V23 applies cleanly.
//   - Device on fork's v21/v22 (content_descriptors, maybe steam_file_hash_cache too): no migration
//     path exists "from" 21 with these exact semantics or "from" 22 at all, so Room falls back to
//     fallbackToDestructiveMigration(true) — an accepted one-time wipe, not a crash. See docs/migrations.md.

// Devices on v20 have none of the three changes yet — no defensive checks needed.
internal val ROOM_MIGRATION_V20_to_V23 = object : Migration(20, 23) {
    override fun migrate(connection: SQLiteConnection) {
        // upstream's v21 change
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `steam_file_hash_cache` " +
                "(`appId` INTEGER NOT NULL, `absPath` TEXT NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, `mtimeMillis` INTEGER NOT NULL, " +
                "`sha` BLOB NOT NULL, PRIMARY KEY(`appId`, `absPath`))"
        )
        // fork's old v22 change
        connection.execSQL(
            "ALTER TABLE steam_app ADD COLUMN content_descriptors TEXT NOT NULL DEFAULT '[]'"
        )
        // upstream's v22 change
        connection.execSQL(
            "ALTER TABLE gog_games ADD COLUMN vertical_cover_url TEXT NOT NULL DEFAULT ''"
        )
    }
}

// v24 adds the precomputed size_bytes column to steam_app. MANUAL migration (not AutoMigration)
// because Room's KSP processor needs a prior schema snapshot (23.json) to diff against to generate
// an AutoMigration body, and 23.json doesn't exist — it was deleted along with the v22 collision's
// schema files during the v22→v23 renumbering (see the comment block above and docs/migrations.md).
//
// Defensive: devices that already ran this fork's pre-renumbering build reached the *old* v24+
// using the same column adds under different version numbers. Room matches migrations purely by
// version number, so a device already on old-v24 (or later) would otherwise hit a duplicate-column
// crash here instead of a clean no-op. Every ALTER TABLE ADD COLUMN below is guarded accordingly.
internal val ROOM_MIGRATION_V23_to_V24 = object : Migration(23, 24) {
    override fun migrate(connection: SQLiteConnection) {
        if (!connection.hasColumn("steam_app", "size_bytes")) {
            connection.execSQL(
                "ALTER TABLE steam_app ADD COLUMN size_bytes INTEGER NOT NULL DEFAULT 0"
            )
        }
        connection.addVerticalCoverUrlIfMissing()
    }
}

// v25 adds the precomputed name_sort_key + is_adult columns to steam_app (for SQL library
// pagination). This is a MANUAL migration, not an AutoMigration, because of the indexes created in
// DatabaseModule's onOpen callback (idx_steam_app_dlc_for_app_id, idx_steam_app_package_id): those
// aren't declared in @Entity, so the expected v25 schema has indices = {}, but the live DB already
// has them. Room only does a full index comparison DURING a migration, so it fails validation with
// "Migration didn't properly handle" — Found has the indexes, Expected has none. We drop them here so
// the post-migration schema matches; onOpen recreates all indexes (incl. the new name_sort_key one)
// right after, before they're next needed.
//
// NOTE for future migrations: because those indexes live in onOpen (not @Entity), every subsequent
// migration must likewise DROP them so post-migration validation sees indices = {}. If that ever
// becomes painful, move the index definitions into @Entity(indices = [...]) and create them via the
// migration with CREATE INDEX IF NOT EXISTS instead.
internal val ROOM_MIGRATION_V24_to_V25 = object : Migration(24, 25) {
    override fun migrate(connection: SQLiteConnection) {
        // Defensive (see ROOM_MIGRATION_V23_to_V24): a device already on old-v25+ already has both
        // columns under the old numbering. Only seed name_sort_key when we're the ones adding it —
        // a device that already had it keeps whatever ICU-refined value the backfill already wrote.
        val hadNameSortKey = connection.hasColumn("steam_app", "name_sort_key")
        if (!hadNameSortKey) {
            connection.execSQL(
                "ALTER TABLE steam_app ADD COLUMN name_sort_key TEXT NOT NULL DEFAULT ''"
            )
        }
        if (!connection.hasColumn("steam_app", "is_adult")) {
            connection.execSQL(
                "ALTER TABLE steam_app ADD COLUMN is_adult INTEGER NOT NULL DEFAULT 0"
            )
        }
        if (!hadNameSortKey) {
            // Seed name_sort_key with an ASCII-lowercased name so the very first post-upgrade render
            // already orders the page by name. Without this, every existing row has name_sort_key = ''
            // until the ICU backfill (backfillSortKeysOnce) runs, so the paginated query's ORDER BY
            // name_sort_key, id falls back to id — showing the lowest-appid rows instead of the
            // alphabetical page. LOWER(name) ≈ the ICU key for Latin titles, so the later backfill
            // refinement is invisible for those; only punctuation/diacritic/non-Latin titles shift
            // slightly once the ICU key lands. One bulk UPDATE, one-time, on the upgrade.
            connection.execSQL("UPDATE steam_app SET name_sort_key = LOWER(name)")
        }
        connection.addVerticalCoverUrlIfMissing()
        // Drop the onOpen-created indexes so the migrated schema matches the (index-less) expected
        // v25 TableInfo. onOpen recreates them (idempotently) on this same open, after validation.
        connection.execSQL("DROP INDEX IF EXISTS idx_steam_app_dlc_for_app_id")
        connection.execSQL("DROP INDEX IF EXISTS idx_steam_app_package_id")
        connection.execSQL("DROP INDEX IF EXISTS idx_steam_app_name_sort_key")
    }
}

// v26 adds store_tags to steam_app and the steam_tag table. MANUAL migration (not AutoMigration)
// for the same reason as v24→v25: the onOpen-created indexes must be dropped so post-migration
// schema validation sees indices = {}. onOpen recreates them immediately after.
internal val ROOM_MIGRATION_V25_to_V26 = object : Migration(25, 26) {
    override fun migrate(connection: SQLiteConnection) {
        // Defensive (see ROOM_MIGRATION_V23_to_V24): a device already on old-v26+ already has this.
        if (!connection.hasColumn("steam_app", "store_tags")) {
            connection.execSQL(
                "ALTER TABLE steam_app ADD COLUMN store_tags TEXT NOT NULL DEFAULT '[]'"
            )
        }
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `steam_tag` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        connection.addVerticalCoverUrlIfMissing()
        connection.execSQL("DROP INDEX IF EXISTS idx_steam_app_dlc_for_app_id")
        connection.execSQL("DROP INDEX IF EXISTS idx_steam_app_package_id")
        connection.execSQL("DROP INDEX IF EXISTS idx_steam_app_name_sort_key")
    }
}

// Devices on upstream's real v21 are missing all three changes — non-defensive, matching
// docs/migrations.md's "simple approach" (a device on the fork's v21/v22 instead would already
// have content_descriptors and hit a duplicate-column error here, which is accepted: it falls
// back to a destructive wipe rather than crashing, since there is no "from=22" path at all).
internal val ROOM_MIGRATION_V21_to_V23 = object : Migration(21, 23) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE steam_app ADD COLUMN content_descriptors TEXT NOT NULL DEFAULT '[]'"
        )
        connection.execSQL(
            "ALTER TABLE gog_games ADD COLUMN vertical_cover_url TEXT NOT NULL DEFAULT ''"
        )
    }
}
