package app.gamenative.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.gamenative.data.SteamLicense
import `in`.dragonbra.javasteam.enums.ELicenseFlags
import `in`.dragonbra.javasteam.enums.ELicenseType
import `in`.dragonbra.javasteam.enums.EPaymentMethod
import java.util.Date
import java.util.EnumSet
import kotlin.math.min

val SQLITE_MAX_VARS = 999

/**
 * The license fields onLicenseList diffs against, plus whether package PICS has filled in
 * app_ids. Skips the large app_ids / depot_ids lists themselves.
 */
data class SteamLicenseStub(
    val packageId: Int,
    @ColumnInfo("last_change_number") val lastChangeNumber: Int,
    @ColumnInfo("license_flags") val licenseFlags: EnumSet<ELicenseFlags>,
    @ColumnInfo("owner_account_id") val ownerAccountId: List<Int>,
    @ColumnInfo("access_token") val accessToken: Long,
    val mapped: Boolean,
)

/**
 * Every [SteamLicense] column except the PICS-derived app_ids / depot_ids, for partial
 * updates that refresh license metadata without wiping the cached mapping.
 */
data class SteamLicenseMeta(
    val packageId: Int,
    @ColumnInfo("last_change_number") val lastChangeNumber: Int,
    @ColumnInfo("time_created") val timeCreated: Date,
    @ColumnInfo("time_next_process") val timeNextProcess: Date,
    @ColumnInfo("minute_limit") val minuteLimit: Int,
    @ColumnInfo("minutes_used") val minutesUsed: Int,
    @ColumnInfo("payment_method") val paymentMethod: EPaymentMethod,
    @ColumnInfo("license_flags") val licenseFlags: EnumSet<ELicenseFlags>,
    @ColumnInfo("purchase_code") val purchaseCode: String,
    @ColumnInfo("license_type") val licenseType: ELicenseType,
    @ColumnInfo("territory_code") val territoryCode: Int,
    @ColumnInfo("access_token") val accessToken: Long,
    @ColumnInfo("owner_account_id") val ownerAccountId: List<Int>,
    @ColumnInfo("master_package_id") val masterPackageID: Int,
)

fun SteamLicense.toMeta() = SteamLicenseMeta(
    packageId = packageId,
    lastChangeNumber = lastChangeNumber,
    timeCreated = timeCreated,
    timeNextProcess = timeNextProcess,
    minuteLimit = minuteLimit,
    minutesUsed = minutesUsed,
    paymentMethod = paymentMethod,
    licenseFlags = licenseFlags,
    purchaseCode = purchaseCode,
    licenseType = licenseType,
    territoryCode = territoryCode,
    accessToken = accessToken,
    ownerAccountId = ownerAccountId,
    masterPackageID = masterPackageID,
)

/** Lightweight package -> app ids projection (avoids loading full license rows). */
data class LicenseAppIds(
    val packageId: Int,
    @ColumnInfo("app_ids") val appIds: List<Int>,
)

@Dao
interface SteamLicenseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(license: List<SteamLicense>)

    @Update
    suspend fun update(license: SteamLicense)

    @Query("UPDATE steam_license SET app_ids = :appIds WHERE packageId = :packageId")
    suspend fun updateApps(packageId: Int, appIds: List<Int>)

    @Query("UPDATE steam_license SET depot_ids = :depotIds WHERE packageId = :packageId")
    suspend fun updateDepots(packageId: Int, depotIds: List<Int>)

    @Query("SELECT * FROM steam_license")
    suspend fun getAllLicenses(): List<SteamLicense>

    @Query("SELECT * FROM steam_license WHERE packageId = :packageId")
    suspend fun findLicense(packageId: Int): SteamLicense?

    @Query("SELECT packageId, app_ids FROM steam_license WHERE app_ids != '[]'")
    suspend fun getLicenseAppIds(): List<LicenseAppIds>

    @Query(
        "SELECT packageId, last_change_number, license_flags, owner_account_id, access_token, " +
            "(app_ids != '[]') AS mapped FROM steam_license",
    )
    suspend fun getLicenseStubs(): List<SteamLicenseStub>

    @Update(entity = SteamLicense::class)
    suspend fun updateMeta(rows: List<SteamLicenseMeta>)

    /* ----------------------------------------------------------
       INTERNAL queries that Room generates.  Keep them abstract.
       ---------------------------------------------------------- */

    @Query(
        "SELECT * FROM steam_license " +
                "WHERE packageId IN (:packageIds)"
    )
    suspend fun _findLicenses(packageIds: List<Int>): List<SteamLicense>

    @Query(
        "SELECT * FROM steam_license " +
                "WHERE packageId NOT IN (:packageIds)"
    )
    suspend fun _findStaleLicences(packageIds: List<Int>): List<SteamLicense>

    @Query(
        "DELETE FROM steam_license " +
                "WHERE packageId IN (:packageIds)"
    )
    suspend fun _deleteStaleLicenses(packageIds: List<Int>)

    /* ----------------------------------------------------------
       PUBLIC wrappers – chunk the list so we never exceed
       SQLite’s 999-parameter ceiling.  These replace the old
       direct queries at call-sites.
       ---------------------------------------------------------- */

    // batched to stay under SQLite's 999 bind-variable limit
    @Transaction
    suspend fun findLicenses(packageIds: List<Int>): List<SteamLicense> {
        if (packageIds.isEmpty()) return emptyList()
        val results = mutableListOf<SteamLicense>()
        for (chunkStart in packageIds.indices step SQLITE_MAX_VARS) {
            val chunkEnd = min(chunkStart + SQLITE_MAX_VARS, packageIds.size)
            results += _findLicenses(packageIds.subList(chunkStart, chunkEnd))
        }
        return results
    }

    // batched NOT IN — intersects chunks so only licenses absent from ALL chunks are returned
    @Transaction
    suspend fun findStaleLicences(packageIds: List<Int>): List<SteamLicense> {
        if (packageIds.isEmpty()) return getAllLicenses()
        val results = mutableListOf<SteamLicense>()
        for (chunkStart in packageIds.indices step SQLITE_MAX_VARS) {
            val chunkEnd = min(chunkStart + SQLITE_MAX_VARS, packageIds.size)
            val chunkResult = _findStaleLicences(packageIds.subList(chunkStart, chunkEnd))
            if (results.isEmpty()) {
                results += chunkResult
            } else {
                results.retainAll(chunkResult)
            }
        }
        return results.distinct()
    }

    @Transaction
    suspend fun deleteStaleLicenses(packageIds: List<Int>) {
        for (chunkStart in packageIds.indices step SQLITE_MAX_VARS) {
            val chunkEnd = min(chunkStart + SQLITE_MAX_VARS, packageIds.size)
            _deleteStaleLicenses(packageIds.subList(chunkStart, chunkEnd))
        }
    }

    @Query("DELETE from steam_license")
    suspend fun deleteAll()
}
