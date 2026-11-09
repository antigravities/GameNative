package app.gamenative.ui.screen.minimal

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp

/**
 * A MinUI list. The selected row sits on a white pill sized to its text; other rows are
 * plain white text. Tap selects; tapping the selected row activates it.
 *
 * Touch scrolls freely. Controller navigation keeps MinUI's paging: when the selection moves
 * off-screen, the list jumps so the selection's page starts at the top.
 */
@Composable
fun <T> MinUiList(
    items: List<T>,
    state: MinUiListState,
    label: (T) -> String,
    onSelectedChange: (Int) -> Unit,
    onActivate: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (T) -> String? = { null },
    dimmed: (T) -> Boolean = { false },
    maxPillWidth: Dp = Dp.Unspecified,
    onPageSize: (Int) -> Unit = {},
    /** Long-press selects the row, then calls this (touch equivalent of Y). Null = disabled. */
    onLongPress: ((Int) -> Unit)? = null,
) {
    val m = rememberMinUiMetrics()
    val scroll = rememberLazyListState()

    // Bring the selection into view only when it isn't fully visible already, so tapping a row
    // after scrolling by touch doesn't make the list jump.
    LaunchedEffect(state.selected, state.pageSize, items.size) {
        if (state.isEmpty) return@LaunchedEffect
        val visible = scroll.layoutInfo.visibleItemsInfo
        val viewportEnd = scroll.layoutInfo.viewportEndOffset
        val fullyVisible = visible.any { it.index == state.selected && it.offset >= 0 && it.offset + it.size <= viewportEnd }
        if (!fullyVisible) scroll.scrollToItem(state.firstVisible)
    }

    BoxWithConstraints(modifier = modifier.padding(horizontal = m.gutter * 0.6f)) {
        // As many whole rows as fit; the owner pages (L1/R1) by this.
        val pageSize = maxOf(1, (maxHeight / m.rowHeight).toInt())
        LaunchedEffect(pageSize) { onPageSize(pageSize) }
        LazyColumn(state = scroll) {
            items(count = items.size) { index ->
                val item = items[index]
                val selected = index == state.selected
                MinUiRow(
                    text = label(item),
                    trailing = trailing(item),
                    selected = selected,
                    dimmed = dimmed(item),
                    maxPillWidth = maxPillWidth,
                    onClick = { if (selected) onActivate(index) else onSelectedChange(index) },
                    onLongClick = onLongPress?.let { cb ->
                        {
                            onSelectedChange(index)
                            cb(index)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun MinUiRow(
    text: String,
    trailing: String?,
    selected: Boolean,
    dimmed: Boolean,
    maxPillWidth: Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val m = rememberMinUiMetrics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(m.rowHeight)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f, fill = false)
                .then(if (maxPillWidth != Dp.Unspecified) Modifier.widthIn(max = maxPillWidth) else Modifier)
                .height(m.rowHeight * 0.8f)
                .then(if (selected) Modifier.background(MinUiColors.Pill, RoundedCornerShape(50)) else Modifier)
                .padding(horizontal = m.gutter * 0.75f),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = text,
                color = when {
                    selected -> MinUiColors.PillText
                    dimmed -> MinUiColors.Dim
                    else -> MinUiColors.Text
                },
                fontFamily = MinUiFont,
                fontSize = m.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            Text(
                text = trailing,
                color = MinUiColors.Dim,
                fontFamily = MinUiFont,
                fontSize = m.smallText,
                maxLines = 1,
                modifier = Modifier.padding(start = m.gutter * 0.5f, end = m.gutter * 0.4f),
            )
        }
    }
}

/** Centered single message in MinUI style (loading / empty states). */
@Composable
fun MinUiMessage(text: String, modifier: Modifier = Modifier) {
    val m = rememberMinUiMetrics()
    Box(modifier = modifier.padding(horizontal = m.gutter), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = MinUiColors.Text,
            fontFamily = MinUiFont,
            fontSize = m.smallText,
            lineHeight = m.smallText * 1.3f,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
