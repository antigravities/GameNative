package app.gamenative.steam

import app.gamenative.data.SteamLicense
import app.gamenative.db.dao.LicenseAppIds
import `in`.dragonbra.javasteam.enums.ELicenseFlags

/**
 * modernMinimal: picks which license package an app should be attributed to, without
 * running package PICS for the whole library. Mirrors the ranking the package PICS loop in
 * SteamService uses, so both paths agree on which package wins.
 */
object MinimalPackageResolver {
    /** 4/3 = preferred family lender (live/expired), 2/1 = user-owned (live/expired), 0 = other. */
    fun licenseRank(license: SteamLicense?, accountId: Int?, preferredLenderAccount: Int?): Int {
        if (license == null) return 0
        val expired = ELicenseFlags.Expired in license.licenseFlags
        if (preferredLenderAccount != null && license.ownerAccountId.contains(preferredLenderAccount)) {
            return if (expired) 3 else 4
        }
        if (accountId == null || !license.ownerAccountId.contains(accountId)) return 0
        return if (expired) 1 else 2
    }

    /**
     * Best package for each of [targetAppIds] among [licenses] that list it. Ties keep the
     * first-seen package. Apps no license mentions are omitted.
     */
    fun pickPackages(
        targetAppIds: Set<Int>,
        licenses: List<LicenseAppIds>,
        rank: (pkgId: Int, appId: Int) -> Int,
    ): Map<Int, Int> {
        if (targetAppIds.isEmpty()) return emptyMap()
        val best = HashMap<Int, Pair<Int, Int>>() // appId -> (pkgId, rank)
        for (license in licenses) {
            for (appId in license.appIds) {
                if (appId !in targetAppIds) continue
                val r = rank(license.packageId, appId)
                val current = best[appId]
                if (current == null || r > current.second) {
                    best[appId] = license.packageId to r
                }
            }
        }
        return best.mapValues { it.value.first }
    }
}
