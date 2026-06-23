package app.gamenative.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.translation.ScreenTranslator
import kotlin.math.roundToInt

/**
 * Draws translated text blocks on top of the game. Each [ScreenTranslator.Block] carries a bounding box
 * in the coordinate space of the OCR image ([ScreenTranslator.OverlayState.imageWidth] x [imageHeight]);
 * we scale those boxes to the on-screen view size and place a semi-opaque black box with the translated
 * text over the original.
 *
 * This composable deliberately adds NO pointer-input modifier, so touches fall through to the game
 * surface below (matching the closed-Quick-Menu behavior). It assumes the game surface fills the view;
 * letterboxed surfaces with a different aspect ratio would need offset/scale correction (TODO).
 */
@Composable
fun ScreenTranslationOverlay(
    state: ScreenTranslator.OverlayState,
    opacity: Float,
    modifier: Modifier = Modifier,
) {
    if (state.blocks.isEmpty() || state.imageWidth <= 0 || state.imageHeight <= 0) return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val scaleX = constraints.maxWidth.toFloat() / state.imageWidth
        val scaleY = constraints.maxHeight.toFloat() / state.imageHeight

        state.blocks.forEach { block ->
            val leftPx = block.left * scaleX
            val topPx = block.top * scaleY
            val widthPx = (block.right - block.left) * scaleX
            val heightPx = (block.bottom - block.top) * scaleY
            if (widthPx <= 0f || heightPx <= 0f) return@forEach

            // Font size derived from the box height (in px → sp), clamped to a readable range. English
            // translations are often longer than the source, so the box grows (see below) and text wraps.
            val fontSp = with(density) {
                (heightPx * 0.55f).toSp().value.coerceIn(9f, 22f)
            }

            // The source box is a MINIMUM, not a fixed size: translations are usually longer than the
            // original, so allow the box to widen (capped to the screen's right edge so it never runs off)
            // and grow downward as wrapped text adds lines, while still covering short source text.
            val maxWidthDp = with(density) { (constraints.maxWidth - leftPx).toDp() } - 4.dp

            Box(
                modifier = Modifier
                    .offset { IntOffset(leftPx.roundToInt(), topPx.roundToInt()) }
                    .widthIn(
                        min = with(density) { widthPx.toDp() },
                        max = maxWidthDp,
                    )
                    .heightIn(min = with(density) { heightPx.toDp() })
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.Black.copy(alpha = opacity)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = block.text,
                    color = Color.White,
                    fontSize = fontSp.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = (fontSp * 1.05f).sp,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
    }
}
