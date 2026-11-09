package app.gamenative.steam

import app.gamenative.db.dao.AppParent
import org.junit.Assert.assertEquals
import org.junit.Test

class MinimalPurgePlannerTest {
    private val none = -1 // SteamService.INVALID_APP_ID-style "no parent"

    @Test fun unwantedUnkeptRowIsDeleted() =
        assertEquals(listOf(10), MinimalPurgePlanner.idsToDelete(listOf(AppParent(10, none)), wanted = setOf(1), keep = emptySet()))

    @Test fun wantedRowIsKept() =
        assertEquals(emptyList<Int>(), MinimalPurgePlanner.idsToDelete(listOf(AppParent(1, none)), wanted = setOf(1), keep = emptySet()))

    @Test fun installedOrphanIsKept() =
        assertEquals(emptyList<Int>(), MinimalPurgePlanner.idsToDelete(listOf(AppParent(5, none)), wanted = setOf(1), keep = setOf(5)))

    @Test fun dlcOfWantedOrKeptParentIsKept() {
        val rows = listOf(AppParent(100, 1), AppParent(500, 5))
        assertEquals(emptyList<Int>(), MinimalPurgePlanner.idsToDelete(rows, wanted = setOf(1), keep = setOf(5)))
    }

    @Test fun dlcOfDeletedParentIsDeleted() {
        val rows = listOf(AppParent(10, none), AppParent(110, 10))
        assertEquals(listOf(10, 110), MinimalPurgePlanner.idsToDelete(rows, wanted = setOf(1), keep = emptySet()))
    }

    @Test fun emptyInputs() =
        assertEquals(emptyList<Int>(), MinimalPurgePlanner.idsToDelete(emptyList(), emptySet(), emptySet()))
}
