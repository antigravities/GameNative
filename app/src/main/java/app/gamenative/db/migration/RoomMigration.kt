package app.gamenative.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

private const val DROP_TABLE = "DROP TABLE IF EXISTS " // Trailing Space

internal val ROOM_MIGRATION_V7_to_V8 = object : Migration(7, 8) {
    override fun migrate(connection: SQLiteConnection) {
        // Dec 5, 2025: Friends and Chat features removed
        connection.execSQL(DROP_TABLE + "chat_message")
        connection.execSQL(DROP_TABLE + "emoticon")
        connection.execSQL(DROP_TABLE + "steam_friend")
    }
}

// v21 schema collision: upstream shipped v21 (adds steam_file_hash_cache) while this fork had
// already shipped its own v21 (adds content_descriptors). The fork is renumbered to v22.
//
// AutoMigration(from=20, to=21) was also dropped because it requires 21.json to exist, and that
// file was deleted (it had unresolvable conflict markers). Instead, two manual migrations cover
// all possible starting states:
//
//   - Device on v20 (ran neither v21): ROOM_MIGRATION_V20_to_V22 applies both changes cleanly.
//   - Device on upstream v21 (steam_file_hash_cache, no content_descriptors): ROOM_MIGRATION_V21_to_V22
//   - Device on fork's v21    (content_descriptors, no steam_file_hash_cache): ROOM_MIGRATION_V21_to_V22

// Devices on v20 have neither change yet — no defensive checks needed.
internal val ROOM_MIGRATION_V20_to_V22 = object : Migration(20, 22) {
    override fun migrate(connection: SQLiteConnection) {
        // upstream's v21 change
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `steam_file_hash_cache` " +
                "(`appId` INTEGER NOT NULL, `absPath` TEXT NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, `mtimeMillis` INTEGER NOT NULL, " +
                "`sha` BLOB NOT NULL, PRIMARY KEY(`appId`, `absPath`))"
        )
        // fork's v22 change
        connection.execSQL(
            "ALTER TABLE steam_app ADD COLUMN content_descriptors TEXT NOT NULL DEFAULT '[]'"
        )
    }
}

// v24 adds the precomputed name_sort_key + is_adult columns to steam_app (for SQL library
// pagination). This is a MANUAL migration, not an AutoMigration, because of the indexes created in
// DatabaseModule's onOpen callback (idx_steam_app_dlc_for_app_id, idx_steam_app_package_id): those
// aren't declared in @Entity, so the expected v24 schema has indices = {}, but the live DB already
// has them. Room only does a full index comparison DURING a migration, so it fails validation with
// "Migration didn't properly handle" — Found has the indexes, Expected has none. We drop them here so
// the post-migration schema matches; onOpen recreates all indexes (incl. the new name_sort_key one)
// right after, before they're next needed.
//
// NOTE for future migrations: because those indexes live in onOpen (not @Entity), every subsequent
// migration must likewise DROP them so post-migration validation sees indices = {}. If that ever
// becomes painful, move the index definitions into @Entity(indices = [...]) and create them via the
// migration with CREATE INDEX IF NOT EXISTS instead.
internal val ROOM_MIGRATION_V23_to_V24 = object : Migration(23, 24) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE steam_app ADD COLUMN name_sort_key TEXT NOT NULL DEFAULT ''"
        )
        connection.execSQL(
            "ALTER TABLE steam_app ADD COLUMN is_adult INTEGER NOT NULL DEFAULT 0"
        )
        // Seed name_sort_key with an ASCII-lowercased name so the very first post-upgrade render
        // already orders the page by name. Without this, every existing row has name_sort_key = ''
        // until the ICU backfill (backfillSortKeysOnce) runs, so the paginated query's ORDER BY
        // name_sort_key, id falls back to id — showing the lowest-appid rows instead of the
        // alphabetical page. LOWER(name) ≈ the ICU key for Latin titles, so the later backfill
        // refinement is invisible for those; only punctuation/diacritic/non-Latin titles shift
        // slightly once the ICU key lands. One bulk UPDATE, one-time, on the upgrade.
        connection.execSQL("UPDATE steam_app SET name_sort_key = LOWER(name)")
        // Drop the onOpen-created indexes so the migrated schema matches the (index-less) expected
        // v24 TableInfo. onOpen recreates them (idempotently) on this same open, after validation.
        connection.execSQL("DROP INDEX IF EXISTS idx_steam_app_dlc_for_app_id")
        connection.execSQL("DROP INDEX IF EXISTS idx_steam_app_package_id")
        connection.execSQL("DROP INDEX IF EXISTS idx_steam_app_name_sort_key")
    }
}

// v25 adds store_tags to steam_app and the steam_tag table. MANUAL migration (not AutoMigration)
// for the same reason as v23→v24: the onOpen-created indexes must be dropped so post-migration
// schema validation sees indices = {}. onOpen recreates them immediately after.
internal val ROOM_MIGRATION_V24_to_V25 = object : Migration(24, 25) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE steam_app ADD COLUMN store_tags TEXT NOT NULL DEFAULT '[]'"
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `steam_tag` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        connection.execSQL("DROP INDEX IF EXISTS idx_steam_app_dlc_for_app_id")
        connection.execSQL("DROP INDEX IF EXISTS idx_steam_app_package_id")
        connection.execSQL("DROP INDEX IF EXISTS idx_steam_app_name_sort_key")
    }
}

// v26 adds the steam_curator + steam_curator_recommendation tables (the curator library filter).
// MANUAL migration (not AutoMigration) for the same reason as v23→v24 / v24→v25: the onOpen-created
// indexes must be dropped so post-migration schema validation sees indices = {}; onOpen recreates
// them immediately after. The CREATE TABLE statements mirror the @Entity column definitions exactly.
internal val ROOM_MIGRATION_V25_to_V26 = object : Migration(25, 26) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `steam_curator` " +
                "(`clan_id` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                "`recommendations_fetched_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`clan_id`))"
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `steam_curator_recommendation` " +
                "(`curator_id` INTEGER NOT NULL, `app_id` INTEGER NOT NULL, " +
                "`recommendation_type` TEXT NOT NULL, `blurb` TEXT NOT NULL DEFAULT '', " +
                "`review_date` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`curator_id`, `app_id`))"
        )
        connection.execSQL("DROP INDEX IF EXISTS idx_steam_app_dlc_for_app_id")
        connection.execSQL("DROP INDEX IF EXISTS idx_steam_app_package_id")
        connection.execSQL("DROP INDEX IF EXISTS idx_steam_app_name_sort_key")
    }
}

// v27 adds review_url to steam_curator_recommendation (the curator review link shown on the game
// detail page). MANUAL migration (not AutoMigration), same reason as the prior migrations: the
// onOpen-created indexes must be dropped so post-migration schema validation sees indices = {}.
internal val ROOM_MIGRATION_V26_to_V27 = object : Migration(26, 27) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE steam_curator_recommendation ADD COLUMN review_url TEXT NOT NULL DEFAULT ''"
        )
        connection.execSQL("DROP INDEX IF EXISTS idx_steam_app_dlc_for_app_id")
        connection.execSQL("DROP INDEX IF EXISTS idx_steam_app_package_id")
        connection.execSQL("DROP INDEX IF EXISTS idx_steam_app_name_sort_key")
    }
}

// Devices on either v21 are missing exactly one of the two changes — both operations are defensive.
internal val ROOM_MIGRATION_V21_to_V22 = object : Migration(21, 22) {
    override fun migrate(connection: SQLiteConnection) {
        // SQLite has no ADD COLUMN IF NOT EXISTS — check via PRAGMA.
        // Fork v21 already has content_descriptors; upstream v21 does not.
        var hasContentDescriptors = false
        connection.prepare("PRAGMA table_info(steam_app)").use { stmt ->
            while (stmt.step()) {
                // Index 1 is the column name in PRAGMA table_info output.
                if (stmt.getText(1) == "content_descriptors") {
                    hasContentDescriptors = true
                    break
                }
            }
        }
        if (!hasContentDescriptors) {
            connection.execSQL(
                "ALTER TABLE steam_app ADD COLUMN content_descriptors TEXT NOT NULL DEFAULT '[]'"
            )
        }

        // CREATE TABLE IF NOT EXISTS is idempotent — upstream v21 already has this table,
        // fork v21 does not.
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `steam_file_hash_cache` " +
                "(`appId` INTEGER NOT NULL, `absPath` TEXT NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, `mtimeMillis` INTEGER NOT NULL, " +
                "`sha` BLOB NOT NULL, PRIMARY KEY(`appId`, `absPath`))"
        )
    }
}
