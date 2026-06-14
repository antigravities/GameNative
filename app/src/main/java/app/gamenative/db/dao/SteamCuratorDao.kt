package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.gamenative.data.SteamCurator

@Dao
interface SteamCuratorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(curators: List<SteamCurator>)

    @Query("SELECT * FROM steam_curator ORDER BY name")
    suspend fun getAll(): List<SteamCurator>

    @Query("SELECT * FROM steam_curator WHERE clan_id = :clanId")
    suspend fun findById(clanId: Long): SteamCurator?

    @Query("SELECT COUNT(*) FROM steam_curator")
    suspend fun count(): Int

    // Replaces the followed-curator list wholesale on a refresh. Recommendations live in a separate
    // table keyed by curator_id and are intentionally NOT deleted here (retention is forever).
    @Query("DELETE FROM steam_curator")
    suspend fun deleteAll()

    // Bumps the per-curator recommendation TTL marker after a successful recommendations refresh.
    @Query("UPDATE steam_curator SET recommendations_fetched_at = :fetchedAt WHERE clan_id = :clanId")
    suspend fun setRecommendationsFetchedAt(clanId: Long, fetchedAt: Long)
}
