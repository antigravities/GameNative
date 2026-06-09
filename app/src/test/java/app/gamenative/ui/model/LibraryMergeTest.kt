package app.gamenative.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [mergeSorted], the two-pointer merge that combines the SQL-ordered Steam refs with
 * the in-memory non-Steam refs into one globally ordered library skeleton. The library's incremental
 * paging slices that skeleton, so a correct (and stable) merge is what keeps pages in the same order
 * a single full sort would have produced.
 */
class LibraryMergeTest {

    private val natural = Comparator<Int> { a, b -> a.compareTo(b) }

    @Test
    fun `interleaves two sorted lists`() {
        val a = listOf(1, 4, 5, 8)
        val b = listOf(2, 3, 6, 7)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), mergeSorted(a, b, natural))
    }

    @Test
    fun `empty inputs`() {
        assertEquals(listOf(1, 2, 3), mergeSorted(listOf(1, 2, 3), emptyList(), natural))
        assertEquals(listOf(1, 2, 3), mergeSorted(emptyList(), listOf(1, 2, 3), natural))
        assertEquals(emptyList<Int>(), mergeSorted(emptyList(), emptyList(), natural))
    }

    @Test
    fun `one list fully precedes the other`() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6), mergeSorted(listOf(1, 2, 3), listOf(4, 5, 6), natural))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), mergeSorted(listOf(4, 5, 6), listOf(1, 2, 3), natural))
    }

    @Test
    fun `ties take the left list first (stable, Steam-before-non-Steam)`() {
        // Tag each element with its source so we can observe ordering on a tie. The comparator keys on
        // the int only, so equal ints from a (left) must come before equal ints from b (right).
        data class Tagged(val key: Int, val src: String)
        val byKey = Comparator<Tagged> { x, y -> x.key.compareTo(y.key) }
        val a = listOf(Tagged(1, "A"), Tagged(2, "A"))
        val b = listOf(Tagged(1, "B"), Tagged(2, "B"))
        val merged = mergeSorted(a, b, byKey)
        assertEquals(listOf("A", "B", "A", "B"), merged.map { it.src })
        assertEquals(listOf(1, 1, 2, 2), merged.map { it.key })
    }
}
