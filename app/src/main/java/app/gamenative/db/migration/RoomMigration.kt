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
