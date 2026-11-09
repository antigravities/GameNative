package app.gamenative.steam

import app.gamenative.data.SteamLicense
import app.gamenative.db.dao.LicenseAppIds
import `in`.dragonbra.javasteam.enums.ELicenseFlags
import `in`.dragonbra.javasteam.enums.ELicenseType
import `in`.dragonbra.javasteam.enums.EPaymentMethod
import java.util.Date
import java.util.EnumSet
import org.junit.Assert.assertEquals
import org.junit.Test

class MinimalPackageResolverTest {
    private val me = 100
    private val lender = 200
    private val stranger = 300

    private fun license(pkg: Int, owner: Int, expired: Boolean = false) = SteamLicense(
        packageId = pkg,
        lastChangeNumber = 1,
        timeCreated = Date(0),
        timeNextProcess = Date(0),
        minuteLimit = 0,
        minutesUsed = 0,
        paymentMethod = EPaymentMethod.None,
        licenseFlags = if (expired) EnumSet.of(ELicenseFlags.Expired) else EnumSet.noneOf(ELicenseFlags::class.java),
        purchaseCode = "",
        licenseType = ELicenseType.SinglePurchase,
        territoryCode = 0,
        accessToken = 0L,
        ownerAccountId = listOf(owner),
        masterPackageID = 0,
    )

    @Test fun rankOrdering() {
        val r = MinimalPackageResolver::licenseRank
        assertEquals(0, r(null, me, null))
        assertEquals(0, r(license(1, stranger), me, null))
        assertEquals(0, r(license(1, me), null, null))
        assertEquals(1, r(license(1, me, expired = true), me, null))
        assertEquals(2, r(license(1, me), me, null))
        assertEquals(3, r(license(1, lender, expired = true), me, lender))
        assertEquals(4, r(license(1, lender), me, lender))
        // preferred lender only applies to licenses that lender owns
        assertEquals(2, r(license(1, me), me, lender))
    }

    @Test fun higherRankWins() {
        val licenses = listOf(LicenseAppIds(10, listOf(440)), LicenseAppIds(20, listOf(440)))
        val ranks = mapOf(10 to 1, 20 to 2)
        val picks = MinimalPackageResolver.pickPackages(setOf(440), licenses) { pkg, _ -> ranks.getValue(pkg) }
        assertEquals(mapOf(440 to 20), picks)
    }

    @Test fun tieKeepsFirstSeen() {
        val licenses = listOf(LicenseAppIds(10, listOf(440)), LicenseAppIds(20, listOf(440)))
        val picks = MinimalPackageResolver.pickPackages(setOf(440), licenses) { _, _ -> 2 }
        assertEquals(mapOf(440 to 10), picks)
    }

    @Test fun onlyTargetsAndUnknownOmitted() {
        val licenses = listOf(LicenseAppIds(10, listOf(440, 441, 442)), LicenseAppIds(20, listOf(570)))
        val picks = MinimalPackageResolver.pickPackages(setOf(440, 570, 999), licenses) { _, _ -> 2 }
        assertEquals(mapOf(440 to 10, 570 to 20), picks)
    }

    @Test fun emptyTargets() =
        assertEquals(
            emptyMap<Int, Int>(),
            MinimalPackageResolver.pickPackages(emptySet(), listOf(LicenseAppIds(10, listOf(440)))) { _, _ -> 2 },
        )
}
