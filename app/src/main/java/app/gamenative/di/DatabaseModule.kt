package app.gamenative.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.gamenative.db.DATABASE_NAME
import app.gamenative.db.PluviaDatabase
import app.gamenative.db.dao.AppInfoDao
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.CachedLicenseDao
import app.gamenative.db.dao.DownloadingAppInfoDao
import app.gamenative.db.dao.EncryptedAppTicketDao
import app.gamenative.db.dao.SteamTagDao
import app.gamenative.db.dao.SteamCuratorDao
import app.gamenative.db.dao.SteamCuratorRecommendationDao
import app.gamenative.db.dao.SteamUnlockedBranchDao
import app.gamenative.db.migration.ROOM_MIGRATION_V7_to_V8
import app.gamenative.db.migration.ROOM_MIGRATION_V20_to_V23
import app.gamenative.db.migration.ROOM_MIGRATION_V21_to_V23
import app.gamenative.db.migration.ROOM_MIGRATION_V23_to_V24
import app.gamenative.db.migration.ROOM_MIGRATION_V24_to_V25
import app.gamenative.db.migration.ROOM_MIGRATION_V25_to_V26
import app.gamenative.db.migration.ROOM_MIGRATION_V26_to_V27
import app.gamenative.db.migration.ROOM_MIGRATION_V27_to_V28
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.Executors
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
class DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PluviaDatabase {
        // The db will be considered unstable during development.
        // Once stable we should add a (room) db migration
        return Room.databaseBuilder(context, PluviaDatabase::class.java, DATABASE_NAME)
            .addMigrations(ROOM_MIGRATION_V7_to_V8, ROOM_MIGRATION_V20_to_V23, ROOM_MIGRATION_V21_to_V23, ROOM_MIGRATION_V23_to_V24, ROOM_MIGRATION_V24_to_V25, ROOM_MIGRATION_V25_to_V26, ROOM_MIGRATION_V26_to_V27, ROOM_MIGRATION_V27_to_V28)
            .fallbackToDestructiveMigration(true)
            // Use SEPARATE executors for queries and transactions. By default Room shares one
            // small fixed pool for both, which deadlocks under load: every open suspend
            // `withTransaction` pins one thread to host the transaction, while the DAO calls
            // inside it need another thread from the same pool to run. With enough concurrent
            // transactions at launch time (cloud-save sync + the PICS product-info pipeline +
            // license processing) the pool is fully consumed by transaction hosts, so the
            // queries they're waiting on can never get a thread — and the whole DB locks up.
            // Cached pools grow on demand and reap idle threads after 60s, so they self-bound;
            // SQLite still serializes writers, so this only removes the scheduling deadlock.
            .setQueryExecutor(Executors.newCachedThreadPool())
            .setTransactionExecutor(Executors.newCachedThreadPool())
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    // These indexes are not declared in @Entity (which would require a schema
                    // migration) — they're created here so we can add them without a version bump
                    // while keeping upstream compatibility. IF NOT EXISTS makes them idempotent.
                    //
                    // dlc_for_app_id: the OWNED_APPS_WHERE DLC EXISTS arm joins back into
                    // steam_app on this column. Without an index, SQLite scans all ~45k rows per
                    // outer row that fails the direct-license check, turning the COUNT(*) query
                    // O(N²) and causing 100+ second runtimes that exhaust the connection pool.
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS idx_steam_app_dlc_for_app_id " +
                            "ON steam_app(dlc_for_app_id)",
                    )
                    // package_id: used in the WHERE clause to exclude INVALID_PKG_ID rows and
                    // as the lookup key for the direct-license EXISTS arm.
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS idx_steam_app_package_id " +
                            "ON steam_app(package_id)",
                    )
                    // (name_sort_key, id): the library pagination query orders by
                    // name_sort_key with id as the tiebreaker, then slices with LIMIT/OFFSET.
                    // This composite index lets SQLite satisfy ORDER BY from the index instead
                    // of materializing + sorting the whole owned set per page. Created here (not
                    // in @Entity) to match the existing index pattern; the column itself is added
                    // by the v24→v25 auto-migration.
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS idx_steam_app_name_sort_key " +
                            "ON steam_app(name_sort_key, id)",
                    )
                }
            })
            .build()
    }

    @Provides
    @Singleton
    fun provideSteamLicenseDao(db: PluviaDatabase) = db.steamLicenseDao()

    @Provides
    @Singleton
    fun provideSteamAppDao(db: PluviaDatabase) = db.steamAppDao()

    @Provides
    @Singleton
    fun provideSteamFileHashCacheDao(db: PluviaDatabase) = db.steamFileHashCacheDao()

    @Provides
    @Singleton
    fun provideAppChangeNumbersDao(db: PluviaDatabase) = db.appChangeNumbersDao()

    @Provides
    @Singleton
    fun provideAppFileChangeListsDao(db: PluviaDatabase) = db.appFileChangeListsDao()

    @Provides
    @Singleton
    fun provideAppInfoDao(db: PluviaDatabase): AppInfoDao = db.appInfoDao()

    @Provides
    @Singleton
    fun provideCachedLicenseDao(db: PluviaDatabase): CachedLicenseDao = db.cachedLicenseDao()

    @Provides
    @Singleton
    fun provideEncryptedAppTicketDao(db: PluviaDatabase): EncryptedAppTicketDao = db.encryptedAppTicketDao()

    @Provides
    @Singleton
    fun provideGOGGameDao(db: PluviaDatabase) = db.gogGameDao()

    @Provides
    @Singleton
    fun provideEpicGameDao(db: PluviaDatabase) = db.epicGameDao()

    @Provides
    @Singleton
    fun provideAmazonGameDao(db: PluviaDatabase) = db.amazonGameDao()

    @Provides
    @Singleton
    fun provideDownloadingAppInfoDao(db: PluviaDatabase): DownloadingAppInfoDao = db.downloadingAppInfoDao()

    @Provides
    @Singleton
    fun provideSteamUnlockedBranchDao(db: PluviaDatabase): SteamUnlockedBranchDao = db.steamUnlockedBranchDao()

    @Provides
    @Singleton
    fun provideSteamTagDao(db: PluviaDatabase): SteamTagDao = db.steamTagDao()

    @Provides
    @Singleton
    fun provideSteamCuratorDao(db: PluviaDatabase): SteamCuratorDao = db.steamCuratorDao()

    @Provides
    @Singleton
    fun provideSteamCuratorRecommendationDao(db: PluviaDatabase): SteamCuratorRecommendationDao =
        db.steamCuratorRecommendationDao()
}
