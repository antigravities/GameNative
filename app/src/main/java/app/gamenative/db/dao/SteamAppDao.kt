package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import app.gamenative.data.OrderedSteamStub
import app.gamenative.data.SteamApp
import app.gamenative.data.SteamAppDepots
import app.gamenative.data.SteamAppSummary
import app.gamenative.service.SteamService.Companion.INVALID_PKG_ID
import app.gamenative.ui.enums.SortOption
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.withIndex

// An app is considered "owned" if there's any non-expired license that grants access
// to it: either its own package's license, or any DLC of it (e.g. for free-to-start
// titles like Diablo IV where the actual purchase is a separate "Standard Edition"
// DLC sub that unlocks the base appid). Without the DLC arm, F2P-with-paid-DLC games
// disappear after the user's free-weekend sub on the base appid expires, even though
// they still own the game via the DLC entitlement.
//
// The two arms are split into two separate EXISTS clauses on purpose: most apps will
// be decided by the first (own-license) arm, which is an O(1) PK lookup on
// steam_license. SQLite short-circuits the second (DLC scan) arm in that common case,
// keeping load times comparable to the original PR #985 query for large libraries.
//
// When :includeExpired is 1, the license predicate is bypassed entirely, surfacing
// apps that are normally hidden (used by the "Expired" library filter for diagnostics).
private const val OWNED_APPS_WHERE =
    "WHERE app.id != 480 " + // Actively filter out Spacewar
    "AND app.package_id != :invalidPkgId " +
    "AND app.type != 0 " +
    "AND (" +
    "  :includeExpired = 1 " +
    "  OR EXISTS (" +
    "    SELECT 1 FROM steam_license AS license " +
    "    WHERE license.packageId = app.package_id " +
    "    AND (license.license_flags & 8) = 0 " + // exclude expired licenses (e.g. free weekends)
    "  ) " +
    "  OR EXISTS (" +
    "    SELECT 1 FROM steam_app AS dlc " +
    "    INNER JOIN steam_license AS license ON dlc.package_id = license.packageId " +
    "    WHERE dlc.dlc_for_app_id = app.id " +
    "    AND (license.license_flags & 8) = 0 " +
    "  ) " +
    ") "

// SQL-expressible library filters, appended after OWNED_APPS_WHERE on the paginated/count queries.
// Each predicate is written so it can be toggled off by a bound parameter without changing the SQL,
// because Room cannot conditionally include clauses:
//   - type:        always applied (the library always has a non-empty type set; the caller returns
//                  early when it is empty, since Room generates invalid SQL for IN ()).
//   - search:      bypassed when :search = '' (LIKE is ASCII case-insensitive only — diacritic
//                  variants won't match, same known limitation as searchOwnedAppSummaries).
//   - adult:       bypassed when :hideAdult = 0; otherwise excludes precomputed is_adult rows.
//   - hidden:      NOT IN (:hiddenIds). Callers MUST pass a non-empty list — use a [-1] sentinel
//                  (no app has id -1) to mean "exclude nothing", so Room never emits IN ().
//   - category:    bypassed when :filterByCategory = 0; otherwise restricts to id IN (:categoryIds).
//                  Callers pass a [-1] sentinel for categoryIds when not filtering, so the IN () case
//                  never arises even though the OR short-circuits it.
private const val LIBRARY_FILTERS =
    "AND app.type IN (:types) " +
    "AND (:search = '' OR LOWER(app.name) LIKE '%' || LOWER(:search) || '%') " +
    "AND (:hideAdult = 0 OR app.is_adult = 0) " +
    "AND app.id NOT IN (:hiddenIds) " +
    "AND (:filterByCategory = 0 OR app.id IN (:categoryIds)) "

// The summary projection (kept in sync with the other *AppSummaries queries). Excludes the heavy
// depots/config/branches/ufs blobs so a page row stays light. Columns are qualified with the `app`
// alias because buildLibraryPageQuery LEFT/INNER JOINs app_info, which also has an `id` column —
// unqualified `id` is ambiguous. The result column names stay unqualified (`id`, `name`, …) so Room's
// SteamAppSummary mapping is unaffected.
private const val SUMMARY_COLS =
    "app.id, app.name, app.type, app.package_id, app.client_icon_hash, app.library_assets, " +
    "app.owner_account_id, app.install_dir, app.content_descriptors, app.size_bytes "

// Projection for buildLibraryPageQuery: SUMMARY returns the full SteamAppSummary columns (one page,
// blobs included); STUB returns only the lightweight OrderedSteamStub columns (id, name_sort_key,
// size_bytes, is_downloaded) used to materialize the full ordered skeleton without loading blobs.
enum class LibraryProjection { SUMMARY, STUB }

// Builds the dynamic library page query for pageOwnedAppSummaries / orderedSteamRows (@RawQuery). The
// ORDER BY differs per [sortOption] and INSTALLED_FIRST/RECENTLY_PLAYED need an app_info join for the
// installed tier, so the SQL can't be a single static @Query. Positional (?) args are appended in
// lockstep with the SQL text. IN-list params (types/hiddenIds/categoryIds/favIds) are expanded to N
// placeholders; callers pass a [-1] sentinel (never a real app id) instead of an empty list so we
// never emit `IN ()`. [types] must be non-empty (the caller returns early otherwise).
//
// installedFilter = true INNER-JOINs app_info on is_downloaded = 1 (the Installed filter chip). The
// INSTALLED_FIRST/RECENTLY_PLAYED sort tier uses app_info.is_downloaded as a proxy for "installed"
// (the badge itself stays filesystem-based in the ViewModel); this keeps the ordering SQL-expressible
// at the cost of a rare folder-present-but-not-in-app_info game not bubbling to the top tier.
//
// [limit] = null omits LIMIT/OFFSET entirely (used by orderedSteamRows to fetch the whole ordered
// set). [projection] selects the SELECT column list; for STUB, is_downloaded is projected from the
// app_info join when present (COALESCE → 0 to avoid a NULL→Boolean mapping crash on the LEFT join)
// and a literal 0 otherwise (name/size sorts don't join app_info and never read the column).
fun buildLibraryPageQuery(
    types: List<Int>,
    search: String,
    hideAdult: Int,
    hiddenIds: List<Int>,
    filterByCategory: Int,
    categoryIds: List<Int>,
    favIds: List<Int>,
    sortOption: SortOption,
    installedFilter: Boolean,
    limit: Int? = null,
    offset: Int = 0,
    invalidPkgId: Int = INVALID_PKG_ID,
    includeExpired: Int = 0,
    projection: LibraryProjection = LibraryProjection.SUMMARY,
): SupportSQLiteQuery {
    fun placeholders(n: Int) = List(n) { "?" }.joinToString(",")
    val args = ArrayList<Any?>()
    val sb = StringBuilder()

    val usesInstalledTier = sortOption == SortOption.INSTALLED_FIRST || sortOption == SortOption.RECENTLY_PLAYED
    val joinedAppInfo = installedFilter || usesInstalledTier
    val selectCols = when (projection) {
        LibraryProjection.SUMMARY -> SUMMARY_COLS
        LibraryProjection.STUB -> {
            val downloaded = if (joinedAppInfo) "COALESCE(app_info.is_downloaded, 0)" else "0"
            "app.id, app.name_sort_key, app.size_bytes, $downloaded AS is_downloaded "
        }
    }
    sb.append("SELECT ").append(selectCols).append("FROM steam_app AS app ")
    if (installedFilter) {
        sb.append("INNER JOIN app_info ON app_info.id = app.id AND app_info.is_downloaded = 1 ")
    } else if (usesInstalledTier) {
        sb.append("LEFT JOIN app_info ON app_info.id = app.id ")
    }

    // OWNED_APPS_WHERE, inlined with positional args (the EXISTS subqueries bind nothing).
    sb.append("WHERE app.id != 480 AND app.package_id != ? AND app.type != 0 AND (")
    args.add(invalidPkgId)
    sb.append("? = 1 ")
    args.add(includeExpired)
    sb.append("OR EXISTS (SELECT 1 FROM steam_license AS license WHERE license.packageId = app.package_id AND (license.license_flags & 8) = 0) ")
    sb.append("OR EXISTS (SELECT 1 FROM steam_app AS dlc INNER JOIN steam_license AS license ON dlc.package_id = license.packageId WHERE dlc.dlc_for_app_id = app.id AND (license.license_flags & 8) = 0)) ")

    // LIBRARY_FILTERS, inlined with positional args (kept in sync with the const).
    sb.append("AND app.type IN (").append(placeholders(types.size)).append(") ")
    args.addAll(types)
    sb.append("AND (? = '' OR LOWER(app.name) LIKE '%' || LOWER(?) || '%') ")
    args.add(search); args.add(search)
    sb.append("AND (? = 0 OR app.is_adult = 0) ")
    args.add(hideAdult)
    sb.append("AND app.id NOT IN (").append(placeholders(hiddenIds.size)).append(") ")
    args.addAll(hiddenIds)
    sb.append("AND (? = 0 OR app.id IN (").append(placeholders(categoryIds.size)).append(")) ")
    args.add(filterByCategory); args.addAll(categoryIds)

    // ORDER BY: favorites-first tier, then the per-option ordering, then id as a stable tiebreaker.
    sb.append("ORDER BY (CASE WHEN app.id IN (").append(placeholders(favIds.size)).append(") THEN 0 ELSE 1 END), ")
    args.addAll(favIds)
    when (sortOption) {
        SortOption.NAME_DESC -> sb.append("app.name_sort_key DESC, app.id ")
        SortOption.SIZE_SMALLEST -> sb.append("app.size_bytes ASC, app.name_sort_key, app.id ")
        SortOption.SIZE_LARGEST -> sb.append("app.size_bytes DESC, app.name_sort_key, app.id ")
        SortOption.INSTALLED_FIRST, SortOption.RECENTLY_PLAYED ->
            sb.append("(CASE WHEN app_info.is_downloaded = 1 THEN 0 ELSE 1 END), app.name_sort_key, app.id ")
        else -> sb.append("app.name_sort_key, app.id ") // NAME_ASC and any future default
    }

    if (limit != null) {
        sb.append("LIMIT ? OFFSET ?")
        args.add(limit); args.add(offset)
    }

    return SimpleSQLiteQuery(sb.toString(), args.toArray())
}

private const val PAGE_SIZE = 500

// Emits the first upstream value immediately, then debounces every subsequent value
// by [timeoutMs]. The plain `.debounce(2000)` previously held *every* value — including
// the first one on a cold open — for 2s of silence, so the library couldn't paint until
// 2s after subscribing. Leading-edge emission removes that floor while still coalescing
// the burst of count changes a PICS sync produces. Implemented via the per-element
// `debounce { ... }` overload (timeout 0 for index 0 = emit now); withIndex/map adapt
// the stream so the index isn't visible downstream.
@OptIn(FlowPreview::class)
private fun <T> Flow<T>.firstThenDebounce(timeoutMs: Long): Flow<T> =
    withIndex()
        .debounce { if (it.index == 0) 0L else timeoutMs }
        .map { it.value }

@Dao
interface SteamAppDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(apps: SteamApp)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<SteamApp>)

    @Update
    suspend fun update(app: SteamApp)

    // Insert a stub row only if the app doesn't already exist. IGNORE (not REPLACE) so a row
    // the app PICS consumer wrote concurrently — with full depot/manifest data — is preserved.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(app: SteamApp)

    // Update only the package_id column so a concurrent depot/manifest write to the same row
    // (from the app PICS consumer) is not clobbered by a whole-row rewrite.
    @Query("UPDATE steam_app SET package_id = :pkgId WHERE id = :appId")
    suspend fun updatePackageId(appId: Int, pkgId: Int)

    // observe change count — triggers re-load without pulling all blobs into one CursorWindow
    @Query(
        "SELECT COUNT(*) FROM steam_app AS app " + OWNED_APPS_WHERE,
    )
    fun _observeOwnedAppCount(
        invalidPkgId: Int = INVALID_PKG_ID,
        includeExpired: Int = 0,
    ): Flow<Int>

    // paged data load — each page fits comfortably in a CursorWindow
    @Query(
        "SELECT * FROM steam_app AS app " + OWNED_APPS_WHERE +
            "ORDER BY LOWER(app.name), app.id LIMIT :limit OFFSET :offset",
    )
    suspend fun _getOwnedAppsPage(
        limit: Int,
        offset: Int,
        invalidPkgId: Int = INVALID_PKG_ID,
        includeExpired: Int = 0,
    ): List<SteamApp>

    // NOT @Transaction on purpose: wrapping this whole multi-page loop in one transaction
    // holds SQLite's write connection for the entire (tens-of-seconds) load on large
    // libraries, blocking PICS inserts and deadlocking the sync pipeline. Without it each
    // page is a short reader that interleaves with writes under WAL; the count observer
    // reloads on the next change if a concurrent insert shifts OFFSET paging.
    suspend fun _getAllOwnedAppsPaged(
        invalidPkgId: Int = INVALID_PKG_ID,
        includeExpired: Int = 0,
    ): List<SteamApp> {
        val result = mutableListOf<SteamApp>()
        var offset = 0
        while (true) {
            // reset per-offset: try full fetch on first page, PAGE_SIZE thereafter
            var pageSize = if (offset == 0) Int.MAX_VALUE else PAGE_SIZE
            while (true) {
                try {
                    val page = _getOwnedAppsPage(pageSize, offset, invalidPkgId, includeExpired)
                    if (page.isEmpty()) return result
                    result += page
                    if (pageSize == Int.MAX_VALUE) return result // got everything in one shot
                    offset += page.size
                    break
                } catch (e: android.database.sqlite.SQLiteBlobTooBigException) {
                    if (pageSize <= 1) throw e // single row exceeds window, can't recover
                    pageSize = if (pageSize == Int.MAX_VALUE) PAGE_SIZE else (pageSize / 2).coerceAtLeast(1)
                }
            }
        }
    }

    // emits full list on count changes, loaded in pages to avoid CursorWindow overflow.
    // property-only updates (name, icon) won't re-emit until the next count change.
    // Pass includeExpired = true to surface apps whose license is flagged Expired or
    // is missing entirely — used by the library "Expired" filter for diagnostics.
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getAllOwnedApps(
        invalidPkgId: Int = INVALID_PKG_ID,
        includeExpired: Boolean = false,
    ): Flow<List<SteamApp>> {
        val includeExpiredFlag = if (includeExpired) 1 else 0
        return _observeOwnedAppCount(invalidPkgId, includeExpiredFlag)
            .firstThenDebounce(2_000) // emit first immediately; debounce later PICS bursts
            .distinctUntilChanged() // skip reload when count unchanged
            .flatMapLatest { // cancel stale reloads during rapid PICS inserts
                flow { emit(_getAllOwnedAppsPaged(invalidPkgId, includeExpiredFlag)) }
            }
    }

    @Query(
        "SELECT id, name, type, package_id, client_icon_hash, library_assets, " +
            "owner_account_id, install_dir, content_descriptors, size_bytes " +
            "FROM steam_app AS app " + OWNED_APPS_WHERE +
            "ORDER BY LOWER(app.name), app.id LIMIT :limit OFFSET :offset",
    )
    suspend fun _getOwnedAppSummariesPage(
        limit: Int,
        offset: Int,
        invalidPkgId: Int = INVALID_PKG_ID,
        includeExpired: Int = 0,
    ): List<SteamAppSummary>

    // Fetches SteamAppSummary rows for a specific set of app IDs.
    // Caller must guard against empty [ids] — Room generates invalid SQL for IN ().
    @Query(
        "SELECT id, name, type, package_id, client_icon_hash, library_assets, " +
            "owner_account_id, install_dir, content_descriptors, size_bytes " +
            "FROM steam_app AS app " + OWNED_APPS_WHERE +
            "AND app.id IN (:ids)",
    )
    suspend fun _getOwnedAppSummariesByIds(
        ids: List<Int>,
        invalidPkgId: Int = INVALID_PKG_ID,
        includeExpired: Int = 0,
    ): List<SteamAppSummary>

    // NOT @Transaction: see _getAllOwnedAppsPaged — a single enclosing transaction here held
    // the write connection for the whole 45k-row library load, blocking PICS inserts and
    // stalling sync (the deadlock this fix targets).
    suspend fun _getAllOwnedAppSummariesPaged(
        invalidPkgId: Int = INVALID_PKG_ID,
        includeExpired: Int = 0,
    ): List<SteamAppSummary> {
        val result = mutableListOf<SteamAppSummary>()
        var offset = 0
        while (true) {
            var pageSize = if (offset == 0) Int.MAX_VALUE else PAGE_SIZE
            while (true) {
                try {
                    val page = _getOwnedAppSummariesPage(pageSize, offset, invalidPkgId, includeExpired)
                    if (page.isEmpty()) return result
                    result += page
                    if (pageSize == Int.MAX_VALUE) return result
                    offset += page.size
                    break
                } catch (e: android.database.sqlite.SQLiteBlobTooBigException) {
                    if (pageSize <= 1) throw e
                    pageSize = if (pageSize == Int.MAX_VALUE) PAGE_SIZE else (pageSize / 2).coerceAtLeast(1)
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getAllOwnedAppSummaries(
        invalidPkgId: Int = INVALID_PKG_ID,
        includeExpired: Boolean = false,
        priorityIds: List<Int> = emptyList(),
        fastFirstRender: Boolean = false,
    ): Flow<List<SteamAppSummary>> {
        val includeExpiredFlag = if (includeExpired) 1 else 0
        // Scoped to this flow instance: getAndSet(true) returns false exactly once,
        // so the priority batch is emitted only on the first inner-flow execution.
        // Without this, every _observeOwnedAppCount re-fire (e.g. a game is added)
        // would re-emit favorites and replace the full list with favorites-only.
        val didFastRender = java.util.concurrent.atomic.AtomicBoolean(false)
        return _observeOwnedAppCount(invalidPkgId, includeExpiredFlag)
            .firstThenDebounce(2_000) // emit first immediately; debounce later PICS bursts
            .distinctUntilChanged()
            .flatMapLatest {
                flow {
                    if (fastFirstRender && !didFastRender.getAndSet(true) && priorityIds.isNotEmpty()) {
                        // Emit favorited rows immediately so the user can launch a game
                        // before the full ~30-second load of all 45k rows completes.
                        val priorityBatch = _getOwnedAppSummariesByIds(
                            priorityIds, invalidPkgId, includeExpiredFlag,
                        )
                        if (priorityBatch.isNotEmpty()) emit(priorityBatch)
                    }
                    emit(_getAllOwnedAppSummariesPaged(invalidPkgId, includeExpiredFlag))
                }
            }
    }

    // Called on the search path only; returns summaries matching both type and name.
    // [types] must be non-empty — Room does not generate valid SQL for an empty IN list.
    // Known limitation: SQLite LIKE is ASCII case-insensitive only, so diacritic variants
    // (e.g. searching "Cafe" will NOT match "Café") are silently excluded on this path.
    // An FTS5 virtual table (proposal #4) would fix this properly.
    @Query(
        "SELECT id, name, type, package_id, client_icon_hash, library_assets, " +
            "owner_account_id, install_dir, content_descriptors, size_bytes " +
            "FROM steam_app AS app " + OWNED_APPS_WHERE +
            "AND app.type IN (:types) " +
            "AND LOWER(app.name) LIKE '%' || LOWER(:searchQuery) || '%' " +
            "ORDER BY LOWER(app.name) ASC",
    )
    suspend fun searchOwnedAppSummaries(
        searchQuery: String,
        types: List<Int>,               // AppType.code values: game=1, application=2, tool=4, demo=8
        invalidPkgId: Int = INVALID_PKG_ID,
        includeExpired: Int = 0,
    ): List<SteamAppSummary>

    // ── SQL-side pagination (library fast path) ──────────────────────────────────
    // These power LibraryViewModel.onFilterApps() so a page load materializes ~pageSize rows
    // instead of the whole owned set. Filtering/sorting/LIMIT all happen in SQLite. See
    // LIBRARY_FILTERS and buildLibraryPageQuery for the parameter conventions (notably the [-1]
    // sentinels that keep Room from emitting an empty `IN ()`).

    // One page of owned summaries, fully filtered, ordered, and LIMIT/OFFSET-sliced. The ORDER BY
    // varies per SortOption (and INSTALLED_FIRST needs an app_info join), so the SQL is built
    // dynamically by [buildLibraryPageQuery] rather than hard-coded in many near-duplicate @Query
    // methods. Returns the SteamAppSummary projection (SUMMARY_COLS).
    @RawQuery
    suspend fun pageOwnedAppSummaries(query: SupportSQLiteQuery): List<SteamAppSummary>

    // The full filtered Steam set, ordered by the active sort, as lightweight stubs (no blobs). Built
    // by buildLibraryPageQuery(projection = STUB, limit = null). Powers the ViewModel's per-filter
    // ordered skeleton so load-more pages are served by ~50-row PK fetches instead of re-running the
    // whole filter/sort. Safe as a single @RawQuery despite ~45k rows because the stub columns carry
    // no large blobs (the per-row CursorWindow overflow that forces adaptive paging elsewhere can't
    // happen here).
    @RawQuery
    suspend fun orderedSteamRows(query: SupportSQLiteQuery): List<OrderedSteamStub>

    // Total matching the same filters — for totalAppsInFilter / pagination math / tab badges.
    @Query(
        "SELECT COUNT(*) FROM steam_app AS app " +
            OWNED_APPS_WHERE + LIBRARY_FILTERS,
    )
    suspend fun countOwnedAppSummaries(
        types: List<Int>,
        search: String,
        hideAdult: Int,
        hiddenIds: List<Int>,
        filterByCategory: Int,
        categoryIds: List<Int>,
        invalidPkgId: Int = INVALID_PKG_ID,
        includeExpired: Int = 0,
    ): Int

    // Installed-only COUNT variant: INNER JOIN app_info on is_downloaded = 1 (matching
    // getInstalledGames). Used by the Installed filter; the filesystem supplement for
    // marker-less/imported installs is merged in by the caller. The installed PAGE itself is served
    // by buildLibraryPageQuery(installedFilter = true).
    @Query(
        "SELECT COUNT(*) FROM steam_app AS app " +
            "INNER JOIN app_info ON app_info.id = app.id AND app_info.is_downloaded = 1 " +
            OWNED_APPS_WHERE + LIBRARY_FILTERS,
    )
    suspend fun countInstalledOwnedAppSummaries(
        types: List<Int>,
        search: String,
        hideAdult: Int,
        hiddenIds: List<Int>,
        filterByCategory: Int,
        categoryIds: List<Int>,
        invalidPkgId: Int = INVALID_PKG_ID,
        includeExpired: Int = 0,
    ): Int

    // Fetches only id + depots for owned apps. Used by the background sizeBytes computation job
    // so that the full depot map does not block the initial library display.
    // Same adaptive-paging pattern as _getAllOwnedAppSummariesPaged to handle CursorWindow limits.
    @Query(
        "SELECT id, depots FROM steam_app AS app " + OWNED_APPS_WHERE +
            "ORDER BY app.id LIMIT :limit OFFSET :offset",
    )
    suspend fun _getOwnedAppDepotsPage(
        limit: Int,
        offset: Int,
        invalidPkgId: Int = INVALID_PKG_ID,
        includeExpired: Int = 0,
    ): List<SteamAppDepots>

    // Writes the precomputed depot size for a single app. Used only by the one-time backfill
    // (SteamService.backfillSizesOnce) that populates rows synced before the size_bytes column
    // existed; steady-state writes happen inline during PICS insert.
    @Query("UPDATE steam_app SET size_bytes = :sizeBytes WHERE id = :appId")
    suspend fun _updateSizeBytes(appId: Int, sizeBytes: Long)

    // Pages owned apps by an ascending-id cursor for the one-time size_bytes backfill. Resume is
    // driven by the persisted cursor (PrefManager.librarySizeBackfillCursor), NOT by a `size_bytes
    // = 0` filter: filtering on size=0 makes matches sparse at high ids (apps that genuinely compute
    // to 0 never drop out), forcing OWNED_APPS_WHERE's correlated EXISTS subqueries to run over a
    // growing range and slowing each page badly. Walking every owned row keeps matches dense; the
    // backfill re-writing an already-correct size is a harmless idempotent write.
    @Query(
        "SELECT id, depots FROM steam_app AS app " + OWNED_APPS_WHERE +
            "AND app.id > :afterId ORDER BY app.id LIMIT :limit",
    )
    suspend fun _getOwnedAppDepotsAfter(
        afterId: Int,
        limit: Int,
        invalidPkgId: Int = INVALID_PKG_ID,
        includeExpired: Int = 0,
    ): List<SteamAppDepots>

    // NOT @Transaction: see _getAllOwnedAppsPaged. This background sizeBytes scan is long too,
    // so a single transaction would contend with PICS writes the same way.
    suspend fun getAllOwnedAppDepotsPaged(invalidPkgId: Int = INVALID_PKG_ID): List<SteamAppDepots> {
        val result = mutableListOf<SteamAppDepots>()
        var offset = 0
        while (true) {
            var pageSize = if (offset == 0) Int.MAX_VALUE else PAGE_SIZE
            while (true) {
                try {
                    val page = _getOwnedAppDepotsPage(pageSize, offset, invalidPkgId)
                    if (page.isEmpty()) return result
                    result += page
                    if (pageSize == Int.MAX_VALUE) return result
                    offset += page.size
                    break
                } catch (e: android.database.sqlite.SQLiteBlobTooBigException) {
                    if (pageSize <= 1) throw e
                    pageSize = if (pageSize == Int.MAX_VALUE) PAGE_SIZE else (pageSize / 2).coerceAtLeast(1)
                }
            }
        }
    }

    // Pages all steam_app rows by an ascending-id cursor for the one-time name_sort_key / is_adult
    // backfill (SteamService.backfillSortKeysOnce). No OWNED_APPS_WHERE here on purpose: the plain
    // `id > :afterId` walk avoids the correlated EXISTS subqueries entirely (cheaper), keeps matches
    // dense for fast cursor resume, and backfills every row so the columns are consistent regardless
    // of current ownership. Re-writing an already-correct value is a harmless idempotent update.
    @Query(
        "SELECT id, name, content_descriptors FROM steam_app " +
            "WHERE id > :afterId ORDER BY id LIMIT :limit",
    )
    suspend fun _getSortKeyBackfillRowsAfter(afterId: Int, limit: Int): List<app.gamenative.data.SteamAppSortKeyRow>

    @Query("UPDATE steam_app SET name_sort_key = :sortKey, is_adult = :isAdult WHERE id = :appId")
    suspend fun _updateSortKeyAndAdult(appId: Int, sortKey: String, isAdult: Boolean)

    @Query(
        "SELECT * FROM steam_app " +
            "WHERE id != 480 " +
            "AND package_id != :invalidPkgId " +
            "AND type != 0 " +
            "ORDER BY LOWER(name)",
    )
    suspend fun getAllOwnedAppsAsList(
        invalidPkgId: Int = INVALID_PKG_ID,
    ): List<SteamApp>

    @Query("SELECT * FROM steam_app WHERE received_pics = 0 AND package_id != :invalidPkgId AND owner_account_id = :ownerId")
    fun getAllOwnedAppsWithoutPICS(
        ownerId: Int,
        invalidPkgId: Int = INVALID_PKG_ID,
    ): List<SteamApp>

    @Query("SELECT * FROM steam_app WHERE id = :appId")
    suspend fun findApp(appId: Int): SteamApp?

    /** Returns all Steam apps sorted by name. */
    @Query("SELECT * FROM steam_app ORDER BY name ASC")
    suspend fun getAllAsList(): List<SteamApp>

    /** Returns installed Steam apps (joined against app_info) sorted by name. */
    @Query("SELECT steam_app.* FROM steam_app INNER JOIN app_info ON steam_app.id = app_info.id WHERE app_info.is_downloaded = 1 ORDER BY steam_app.name ASC")
    suspend fun getInstalledGames(): List<SteamApp>

    @Query("SELECT * FROM steam_app WHERE id = :appId")
    fun observeApp(appId: Int): Flow<SteamApp?>

    @Query("SELECT * FROM steam_app AS app WHERE dlc_for_app_id = :appId AND depots <> '{}' AND " +
            " EXISTS (" +
            "   SELECT * FROM steam_license AS license " +
            "     WHERE license.license_type <> 0 AND " +
            "       REPLACE(REPLACE(license.app_ids, '[', ','), ']', ',') LIKE ('%,' || app.id || ',%') " +
            ")"
    )
    suspend fun findDownloadableDLCApps(appId: Int): List<SteamApp>?

    @Query("SELECT * FROM steam_app AS app WHERE dlc_for_app_id = :appId AND depots = '{}' AND " +
            " EXISTS (" +
            "   SELECT * FROM steam_license AS license " +
            "     WHERE license.license_type <> 0 AND " +
            "       REPLACE(REPLACE(license.app_ids, '[', ','), ']', ',') LIKE ('%,' || app.id || ',%') " +
            ")"
    )
    suspend fun findHiddenDLCApps(appId: Int): List<SteamApp>?

    @Query("DELETE from steam_app")
    suspend fun deleteAll()

    @Query("SELECT id FROM steam_app ORDER BY id ASC")
    suspend fun getAllAppIds(): List<Int>

    // Returns the subset of `ids` that already have a full PICS sync recorded
    // (received_pics = 1). Used by the package PICS processor to skip apps that
    // PICSChangesCheck keeps current via incremental changelists. No schema change
    // needed — received_pics already exists on the steam_app table.
    @Query("SELECT id FROM steam_app WHERE id IN (:ids) AND received_pics = 1")
    suspend fun findSyncedIds(ids: Collection<Int>): List<Int>

    @Query("UPDATE steam_app SET workshop_mods = :workshopMods, enabled_workshop_item_ids = :enabledIds WHERE id = :appId")
    suspend fun updateWorkshopState(appId: Int, workshopMods: Boolean, enabledIds: String)

    @Query("SELECT workshop_mods FROM steam_app WHERE id = :appId")
    suspend fun getWorkshopMods(appId: Int): Boolean?

    @Query("SELECT enabled_workshop_item_ids FROM steam_app WHERE id = :appId")
    suspend fun getEnabledWorkshopItemIds(appId: Int): String?

    @Query("UPDATE steam_app SET workshop_download_pending = :pending WHERE id = :appId")
    suspend fun setWorkshopDownloadPending(appId: Int, pending: Boolean)

    @Query("SELECT id FROM steam_app WHERE workshop_download_pending = 1 AND workshop_mods = 1 AND enabled_workshop_item_ids != ''")
    suspend fun getAppsWithPendingWorkshopDownloads(): List<Int>

    @Query("UPDATE steam_app SET workshop_mods = 0, enabled_workshop_item_ids = '', workshop_download_pending = 0 WHERE id = :appId")
    suspend fun clearWorkshopState(appId: Int)

    @Query("SELECT * FROM steam_app WHERE config LIKE '%\"installDir\":\"' || :dirName || '\",%'")
    suspend fun findSteamAppWithInstallDir(dirName: String): List<SteamApp>

    // One-time data backfill: the install_dir flat column was always empty because KeyValueUtils
    // was reading the wrong PICS key. This copies the correct value from the config JSON blob
    // so SteamAppSummary can find it without loading the full blob on every filter call.
    // The WHERE guard makes it safe to re-run; rows already fixed are skipped.
    @Query("""
        UPDATE steam_app
        SET install_dir = json_extract(config, '${'$'}.installDir')
        WHERE install_dir = ''
          AND json_extract(config, '${'$'}.installDir') IS NOT NULL
          AND json_extract(config, '${'$'}.installDir') != ''
    """)
    suspend fun backfillInstallDirsFromConfig()

    @Query("SELECT * FROM steam_app WHERE id IN (:appIds)")
    suspend fun findSteamAppWithAppIds(appIds: List<Int>): List<SteamApp>

    // Reverse-lookup by on-disk directory name — used by the Storage tab to identify
    // game dirs that exist without a corresponding AppInfo entry (recovery path).
    // install_dir mirrors config.installDir; when blank, the game is installed under
    // app.name instead (matching SteamService.getAppDirName() logic).
    @Query("""
        SELECT * FROM steam_app
        WHERE install_dir = :dirName
           OR (install_dir = '' AND name = :dirName)
        LIMIT 1
    """)
    suspend fun findSteamAppByDirName(dirName: String): SteamApp?
}
