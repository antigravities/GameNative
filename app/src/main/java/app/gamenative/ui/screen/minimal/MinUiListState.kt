package app.gamenative.ui.screen.minimal

/**
 * Pure selection/paging state for a MinUI list. Up/down wraps at the ends (like MinUI);
 * page jumps clamp. Only one page is shown at a time, starting at [firstVisible].
 */
data class MinUiListState(
    val selected: Int = 0,
    val size: Int = 0,
    val pageSize: Int = 6,
) {
    init {
        require(pageSize > 0) { "pageSize must be positive" }
    }

    val isEmpty: Boolean get() = size <= 0

    /** First index of the page containing [selected]. */
    val firstVisible: Int get() = if (isEmpty) 0 else (selected / pageSize) * pageSize

    /** Indices of the visible page. */
    val visible: IntRange get() = if (isEmpty) IntRange.EMPTY else firstVisible until minOf(firstVisible + pageSize, size)

    fun moveBy(delta: Int): MinUiListState {
        if (isEmpty) return this
        return copy(selected = Math.floorMod(selected + delta, size))
    }

    fun pageBy(delta: Int): MinUiListState {
        if (isEmpty) return this
        return copy(selected = (selected + delta * pageSize).coerceIn(0, size - 1))
    }

    /** Adapt to a new item count / page size, keeping the selection in range. */
    fun resized(newSize: Int, newPageSize: Int = pageSize): MinUiListState =
        MinUiListState(
            selected = if (newSize <= 0) 0 else selected.coerceIn(0, newSize - 1),
            size = newSize,
            pageSize = newPageSize,
        )
}
