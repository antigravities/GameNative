package app.gamenative.ui.screen.minimal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** A bottom-bar hint; with [onClick] the pill is also tappable (for touch without a controller). */
data class MinUiHint(val button: String, val label: String, val onClick: (() -> Unit)? = null)

/** Bottom bar of MinUI hint pills: dark rounded pill, light chip with the button, then the label. */
@Composable
fun MinUiHintBar(
    left: List<MinUiHint>,
    right: List<MinUiHint>,
    modifier: Modifier = Modifier,
) {
    val m = rememberMinUiMetrics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = m.gutter * 0.6f),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(m.gutter * 0.4f)) { left.forEach { MinUiHintPill(it) } }
        Row(horizontalArrangement = Arrangement.spacedBy(m.gutter * 0.4f)) { right.forEach { MinUiHintPill(it) } }
    }
}

@Composable
fun MinUiHintPill(hint: MinUiHint) {
    val m = rememberMinUiMetrics()
    val height = m.hintHeight
    val chipHeight = height * 0.74f
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .height(height)
            .clip(shape)
            .then(if (hint.onClick != null) Modifier.clickable(onClick = hint.onClick) else Modifier)
            .background(MinUiColors.HintPill, shape)
            .padding(start = (height - chipHeight) / 2, end = m.gutter * 0.6f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Single letters get a circle; longer names (POWER, START) a pill.
        Box(
            modifier = Modifier
                .height(chipHeight)
                .widthIn(min = chipHeight)
                .background(MinUiColors.HintChip, RoundedCornerShape(50))
                .padding(horizontal = if (hint.button.length > 1) chipHeight * 0.3f else 0.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = hint.button,
                color = MinUiColors.HintPill,
                fontFamily = MinUiFont,
                fontSize = m.smallText,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
            )
        }
        Text(
            text = hint.label,
            color = MinUiColors.Text,
            fontFamily = MinUiFont,
            fontSize = m.smallText,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(start = m.gutter * 0.35f),
        )
    }
}
