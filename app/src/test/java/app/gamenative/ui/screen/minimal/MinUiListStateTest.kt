package app.gamenative.ui.screen.minimal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MinUiListStateTest {
    private fun s(selected: Int, size: Int, page: Int = 6) = MinUiListState(selected, size, page)

    @Test fun moveWrapsDownAtEnd() = assertEquals(0, s(9, 10).moveBy(1).selected)

    @Test fun moveWrapsUpAtStart() = assertEquals(9, s(0, 10).moveBy(-1).selected)

    @Test fun moveWithinRange() = assertEquals(4, s(3, 10).moveBy(1).selected)

    @Test fun pageDownClampsToLast() {
        assertEquals(6, s(0, 10).pageBy(1).selected)
        assertEquals(9, s(6, 10).pageBy(1).selected)
    }

    @Test fun pageUpClampsToFirst() {
        assertEquals(1, s(7, 10).pageBy(-1).selected)
        assertEquals(0, s(3, 10).pageBy(-1).selected)
    }

    @Test fun firstVisibleFollowsSelection() {
        assertEquals(0, s(5, 20).firstVisible)
        assertEquals(6, s(6, 20).firstVisible)
        assertEquals(18, s(19, 20).firstVisible)
        assertEquals(18 until 20, s(19, 20).visible)
    }

    @Test fun emptyListIsInert() {
        val e = s(0, 0)
        assertTrue(e.visible.isEmpty())
        assertEquals(e, e.moveBy(1))
        assertEquals(e, e.pageBy(1))
    }

    @Test fun singleItem() {
        assertEquals(0, s(0, 1).moveBy(1).selected)
        assertEquals(0 until 1, s(0, 1).visible)
    }

    @Test fun exactlyOnePage() {
        assertEquals(0 until 6, s(5, 6).visible)
        assertEquals(0, s(5, 6).moveBy(1).selected)
    }

    @Test fun resizedClampsSelection() {
        assertEquals(2, s(8, 10).resized(3).selected)
        assertEquals(0, s(8, 10).resized(0).selected)
    }
}
