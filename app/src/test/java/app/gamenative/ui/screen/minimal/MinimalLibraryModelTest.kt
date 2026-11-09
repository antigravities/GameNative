package app.gamenative.ui.screen.minimal

import app.gamenative.db.dao.MinimalAppRow
import app.gamenative.enums.AppType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MinimalLibraryModelTest {
    private val noParent = Int.MAX_VALUE

    private fun row(id: Int, name: String, type: AppType = AppType.game, pics: Boolean = true, parent: Int = noParent) =
        MinimalAppRow(id, name, type, parent, pics)

    @Test fun collectionAppsShownSortedCaseInsensitive() {
        val rows = listOf(row(1, "zeta"), row(2, "Alpha"), row(3, "beta"))
        val c = buildMinimalLibrary(rows, setOf(1, 2, 3), emptySet(), emptyMap())
        assertEquals(listOf("Alpha", "beta", "zeta"), c.entries.map { it.name })
        assertEquals(0, c.pendingCount)
    }

    @Test fun rowsWithoutPicsArePending() {
        val rows = listOf(row(1, "A"), row(2, "", pics = false))
        val c = buildMinimalLibrary(rows, setOf(1, 2, 3), emptySet(), emptyMap())
        assertEquals(listOf(1), c.entries.map { it.appId })
        // id 3 has no row at all (unowned / unmapped) and is not counted
        assertEquals(1, c.pendingCount)
    }

    @Test fun dlcAndUnsupportedTypesHidden() {
        val rows = listOf(
            row(1, "Game"),
            row(2, "Soundtrack", type = AppType.dlc),
            row(3, "Some DLC", parent = 1),
            row(4, "Video", type = AppType.video),
            row(5, "Tool", type = AppType.tool),
        )
        val c = buildMinimalLibrary(rows, setOf(1, 2, 3, 4, 5), emptySet(), emptyMap())
        assertEquals(listOf(1, 5), c.entries.map { it.appId }.sorted())
    }

    @Test fun installedOutsideCollectionIsOrphan() {
        val c = buildMinimalLibrary(listOf(row(1, "A"), row(9, "Old")), setOf(1), setOf(9), emptyMap())
        val old = c.entries.single { it.appId == 9 }
        assertTrue(old.orphan)
        assertTrue(old.installed)
    }

    @Test fun notInstalledOutsideCollectionHidden() {
        val c = buildMinimalLibrary(listOf(row(1, "A"), row(9, "Old")), setOf(1), emptySet(), emptyMap())
        assertEquals(listOf(1), c.entries.map { it.appId })
    }

    @Test fun nonEmptyCollectionWithNoRowsYetIsSyncingNotEmpty() {
        // First login: packages not mapped yet, so no rows exist for the collection's apps.
        val c = buildMinimalLibrary(emptyList(), setOf(1, 2), emptySet(), emptyMap())
        assertTrue(c.entries.isEmpty())
        assertEquals(2, c.collectionSize)
        assertTrue(c.isSyncing)
    }

    @Test fun emptyCollectionIsNotSyncing() {
        val c = buildMinimalLibrary(emptyList(), emptySet(), emptySet(), emptyMap())
        assertEquals(false, c.isSyncing)
    }

    @Test fun startingClearsOnceDownloadIsRunning() {
        val rows = listOf(row(1, "A"), row(2, "B"))
        val c = buildMinimalLibrary(rows, setOf(1, 2), emptySet(), mapOf(2 to 0), starting = setOf(1, 2))
        assertTrue(c.entries.single { it.appId == 1 }.starting)
        // Job exists (even at 0%): show the percentage, not "installing".
        assertEquals(false, c.entries.single { it.appId == 2 }.starting)
        assertEquals(0, c.entries.single { it.appId == 2 }.downloadPercent)
    }

    @Test fun downloadPercentPassedThrough() {
        val c = buildMinimalLibrary(listOf(row(1, "A"), row(2, "B")), setOf(1, 2), emptySet(), mapOf(1 to 42))
        assertEquals(42, c.entries.single { it.appId == 1 }.downloadPercent)
        assertNull(c.entries.single { it.appId == 2 }.downloadPercent)
    }
}
