package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.gamenative.data.SteamTag

@Dao
interface SteamTagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tags: List<SteamTag>)

    @Query("SELECT * FROM steam_tag ORDER BY name")
    suspend fun getAll(): List<SteamTag>

    @Query("SELECT COUNT(*) FROM steam_tag")
    suspend fun count(): Int
}
