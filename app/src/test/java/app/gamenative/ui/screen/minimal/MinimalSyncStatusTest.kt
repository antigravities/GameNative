package app.gamenative.ui.screen.minimal

import app.gamenative.data.MinimalSyncProgress.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MinimalSyncStatusTest {
    @Test fun shortShowsPackagePercentFirst() =
        assertEquals("Packages 25%", syncStatusShort(Progress(packagesDone = 50, packagesTotal = 200, appsTotal = 10), 3))

    @Test fun shortFallsBackToGamesThenPending() {
        assertTrue(syncStatusShort(Progress(packagesDone = 200, packagesTotal = 200, appsDone = 1, appsTotal = 4), 0)!!.startsWith("Games 1"))
        assertEquals("Syncing 3…", syncStatusShort(Progress(), 3))
        assertNull(syncStatusShort(Progress(), 0))
    }

    @Test fun doneIsClampedToTotal() {
        // Change-checker batches also count as done; never show more than the total, and an
        // overshoot means the package phase is over.
        val over = Progress(packagesDone = 300, packagesTotal = 256)
        assertEquals(256, over.packagesShown)
        assertEquals(false, over.packagesActive)
    }

    @Test fun longListsKnownCounters() {
        val text = syncStatusLong(Progress(packagesDone = 1, packagesTotal = 2))
        assertTrue(text.startsWith("Syncing games"))
        assertTrue(text.contains("Packages 1 / 2"))
        assertTrue(!text.contains("Games "))
    }
}
