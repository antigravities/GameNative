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
