package app.gamenative.steam

import app.gamenative.steam.IncrementalLicenseSync.Existing
import app.gamenative.steam.IncrementalLicenseSync.Fingerprint
import `in`.dragonbra.javasteam.enums.ELicenseFlags
import org.junit.Assert.assertEquals
import org.junit.Test

class IncrementalLicenseSyncTest {
    private fun fp(pkg: Int, change: Int = 10, flags: Set<ELicenseFlags> = emptySet(), owners: Set<Int> = setOf(1), token: Long = 0L) =
        Fingerprint(pkg, change, flags, owners, token)

    private fun plan(incoming: List<Fingerprint>, existing: List<Existing>) = IncrementalLicenseSync.plan(incoming, existing)

    @Test fun newPackageIsFullWrite() =
        assertEquals(setOf(1), plan(listOf(fp(1)), emptyList()).fullWrite)

    @Test fun changedChangeNumberIsFullWrite() =
        assertEquals(setOf(1), plan(listOf(fp(1, change = 11)), listOf(Existing(fp(1), mapped = true))).fullWrite)

    @Test fun unmappedPackageIsFullWriteEvenIfUnchanged() =
        assertEquals(setOf(1), plan(listOf(fp(1)), listOf(Existing(fp(1), mapped = false))).fullWrite)

    @Test fun unchangedMappedPackageIsUntouched() {
        val p = plan(listOf(fp(1)), listOf(Existing(fp(1), mapped = true)))
        assertEquals(emptySet<Int>(), p.fullWrite)
        assertEquals(emptySet<Int>(), p.metaOnly)
        assertEquals(emptyList<Int>(), p.stale)
    }

    @Test fun flagsOwnersOrTokenChangeIsMetaOnly() {
        val existing = listOf(Existing(fp(1), true), Existing(fp(2), true), Existing(fp(3), true))
        val incoming = listOf(
            fp(1, flags = setOf(ELicenseFlags.Expired)),
            fp(2, owners = setOf(1, 2)),
            fp(3, token = 42L),
        )
        val p = plan(incoming, existing)
        assertEquals(setOf(1, 2, 3), p.metaOnly)
        assertEquals(emptySet<Int>(), p.fullWrite)
    }

    @Test fun ownerOrderDoesNotMatter() =
        assertEquals(
            emptySet<Int>(),
            plan(listOf(fp(1, owners = setOf(2, 1))), listOf(Existing(fp(1, owners = setOf(1, 2)), true))).metaOnly,
        )

    @Test fun storedButNotIncomingIsStale() =
        assertEquals(listOf(2), plan(listOf(fp(1)), listOf(Existing(fp(1), true), Existing(fp(2), true))).stale)
}
