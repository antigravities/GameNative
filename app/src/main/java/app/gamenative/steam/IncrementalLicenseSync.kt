package app.gamenative.steam

import `in`.dragonbra.javasteam.enums.ELicenseFlags

/**
 * Diffs the license list Steam sends on login against what's already stored, so
 * onLicenseList only writes (and re-requests) what actually changed. Pure, so it can run
 * outside any DB transaction and be unit-tested.
 */
object IncrementalLicenseSync {
    /** The fields of a license that we compare. */
    data class Fingerprint(
        val packageId: Int,
        val changeNumber: Int,
        val flags: Set<ELicenseFlags>,
        val owners: Set<Int>,
        val accessToken: Long,
    )

    /** A stored license: its fingerprint plus whether package PICS has filled in its app ids. */
    data class Existing(val fingerprint: Fingerprint, val mapped: Boolean)

    data class Plan(
        /**
         * New packages, packages whose change number moved, and packages whose app mapping is
         * still empty: fully (re)written and sent to package PICS.
         */
        val fullWrite: Set<Int>,
        /** Same change number and mapped, but flags / owners / token differ: metadata-only update. */
        val metaOnly: Set<Int>,
        /** Stored but no longer licensed. */
        val stale: List<Int>,
    )

    fun plan(incoming: List<Fingerprint>, existing: List<Existing>): Plan {
        val stored = existing.associateBy { it.fingerprint.packageId }
        val fullWrite = HashSet<Int>()
        val metaOnly = HashSet<Int>()
        val incomingIds = HashSet<Int>(incoming.size * 2)
        for (fp in incoming) {
            incomingIds += fp.packageId
            val old = stored[fp.packageId]
            when {
                old == null || !old.mapped || old.fingerprint.changeNumber != fp.changeNumber -> fullWrite += fp.packageId
                old.fingerprint != fp -> metaOnly += fp.packageId
            }
        }
        val stale = existing.mapNotNull { e -> e.fingerprint.packageId.takeIf { it !in incomingIds } }
        return Plan(fullWrite, metaOnly, stale)
    }
}
