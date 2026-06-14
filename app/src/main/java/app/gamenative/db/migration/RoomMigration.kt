package app.gamenative.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

private const val DROP_TABLE = "DROP TABLE IF EXISTS " // Trailing Space

// SQLite has no ADD COLUMN IF NOT EXISTS — check via PRAGMA before a defensive column add.
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

internal val ROOM_MIGRATION_V7_to_V8 = object : Migration(7, 8) {
    override fun migrate(connection: SQLiteConnection) {
        // Dec 5, 2025: Friends and Chat features removed
        connection.execSQL(DROP_TABLE + "chat_message")
        connection.execSQL(DROP_TABLE + "emoticon")
        connection.execSQL(DROP_TABLE + "steam_friend")
    }
}

// v29 is MANUAL (not AutoMigration) because it must reconcile devices that ran the pre-rebase fork
// build: the fork's final DB version is also 28, but its schema diverges from ours at the same
// integer version, so only this 28→29 step runs for those devices and it must bring their schema up
// to the expected v29. Every statement below is idempotent, so on a master-origin device (which
// reached v29 through the full auto-migration chain) all of it is a no-op except the review_url add.
internal val ROOM_MIGRATION_V28_to_V29 = object : Migration(28, 29) {
    override fun migrate(connection: SQLiteConnection) {
        // review_url: the fork added it at its own v28, so guard against a duplicate-column crash.
        if (!connection.hasColumn("steam_curator_recommendation", "review_url")) {
            connection.execSQL(
                "ALTER TABLE steam_curator_recommendation ADD COLUMN review_url TEXT NOT NULL DEFAULT ''",
            )
        }
        // library_play_history: a master-origin table (added at master's v23) that the fork never
        // had; a device at the fork's integer v28 skipped the v22→23 auto-migration that creates it.
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `library_play_history` (`app_id` TEXT NOT NULL, " +
                "`last_played` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`app_id`))",
        )
        // steam_app indices: our @Entity indices (index_steam_app_*) are created by the 25→26
        // auto-migration on the normal path; a fork-v28 device skipped it and instead carries the
        // fork's old onOpen indexes (idx_steam_app_*). Create the expected ones and drop the fork's
        // so the post-migration TableInfo matches v29.
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_steam_app_dlc_for_app_id` ON `steam_app` (`dlc_for_app_id`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_steam_app_package_id` ON `steam_app` (`package_id`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_steam_app_name_sort_key_id` ON `steam_app` (`name_sort_key`, `id`)")
        connection.execSQL("DROP INDEX IF EXISTS `idx_steam_app_dlc_for_app_id`")
        connection.execSQL("DROP INDEX IF EXISTS `idx_steam_app_package_id`")
        connection.execSQL("DROP INDEX IF EXISTS `idx_steam_app_name_sort_key`")
    }
}
