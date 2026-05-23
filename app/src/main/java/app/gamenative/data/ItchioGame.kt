package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * itch.io game entity for Room database.
 * Fields will be expanded once real API sync is implemented.
 */
@Entity(tableName = "itchio_game")
data class ItchioGame(
    // String ID matching the itch.io game ID format (e.g., "123456")
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,

    @ColumnInfo("title")
    val title: String = "",

    // Cover image URL returned by the itch.io API
    @ColumnInfo("image_url")
    val imageUrl: String = "",

    @ColumnInfo("is_installed")
    val isInstalled: Boolean = false,

    @ColumnInfo("install_path")
    val installPath: String = "",
)
