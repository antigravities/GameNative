package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.gamenative.data.SteamCuratorRecommendation

@Dao
interface SteamCuratorRecommendationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(recommendations: List<SteamCuratorRecommendation>)

    // App ids this curator reviewed (recommended/informational only, since that's all we store).
    // Used both to load the in-memory filter set and as the source for the SQL-path subquery.
    @Query("SELECT app_id FROM steam_curator_recommendation WHERE curator_id = :curatorId")
    suspend fun getAppIds(curatorId: Long): List<Int>

    // For the future game-detail-page feature: look up this curator's review of one app.
    @Query("SELECT * FROM steam_curator_recommendation WHERE curator_id = :curatorId AND app_id = :appId")
    suspend fun getRecommendation(curatorId: Long, appId: Int): SteamCuratorRecommendation?

    @Query("DELETE FROM steam_curator_recommendation WHERE curator_id = :curatorId")
    suspend fun deleteByCurator(curatorId: Long)

    // Debug-only: wipe every curator's cached recommendations so they re-fetch from Steam.
    @Query("DELETE FROM steam_curator_recommendation")
    suspend fun deleteAll()
}
