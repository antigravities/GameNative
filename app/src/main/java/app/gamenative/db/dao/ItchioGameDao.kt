package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.gamenative.data.ItchioGame
import kotlinx.coroutines.flow.Flow

@Dao
interface ItchioGameDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(game: ItchioGame)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(games: List<ItchioGame>)

    @Update
    suspend fun update(game: ItchioGame)

    @Delete
    suspend fun delete(game: ItchioGame)

    @Query("DELETE FROM itchio_game WHERE id = :gameId")
    suspend fun deleteById(gameId: String)

    @Query("SELECT * FROM itchio_game WHERE id = :gameId")
    suspend fun getById(gameId: String): ItchioGame?

    @Query("SELECT * FROM itchio_game ORDER BY title ASC")
    fun getAll(): Flow<List<ItchioGame>>

    @Query("SELECT * FROM itchio_game WHERE is_installed = :isInstalled ORDER BY title ASC")
    fun getByInstallStatus(isInstalled: Boolean): Flow<List<ItchioGame>>

    @Query("SELECT COUNT(*) FROM itchio_game")
    fun getCount(): Flow<Int>

    @Query("DELETE FROM itchio_game WHERE is_installed = 0")
    suspend fun deleteAllNonInstalledGames()
}
