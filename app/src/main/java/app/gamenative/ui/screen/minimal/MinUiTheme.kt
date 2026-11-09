package app.gamenative.ui.screen.minimal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import app.gamenative.R

/** Colours and type for the MinUI-style launcher (modernMinimal). */
object MinUiColors {
    val Background = Color(0xFF000000)
    val Text = Color(0xFFFFFFFF)
    val Pill = Color(0xFFFFFFFF)
    val PillText = Color(0xFF151515)
    val HintPill = Color(0xFF2B2B2B)
    val HintChip = Color(0xFFCFCFCF)
    val Dim = Color(0xFF8A8A8A)
}

/** Nunito (variable) pinned to ExtraBold, close to MinUI's heavy rounded face. */
@OptIn(ExperimentalTextApi::class)
val MinUiFont: FontFamily = FontFamily(
    Font(
        R.font.nunito_variable,
        weight = FontWeight.ExtraBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(800)),
    ),
)

/**
 * Sizes for MinUI screens. On a small landscape handheld this matches MinUI's 480p layout
 * (row = 1/8 of the height); on phones in portrait and on large screens the row height is
 * based on the shorter edge and capped, so rows don't balloon. Rows per page are measured
 * by the list itself from the space it gets.
 */
data class MinUiMetrics(
    val rowHeight: Dp,
    val text: TextUnit,
    val smallText: TextUnit,
    val gutter: Dp,
    val hintHeight: Dp,
    val isPortrait: Boolean,
)

private const val MIN_ROW_DP = 40f
private const val MAX_ROW_DP = 64f

@Composable
fun rememberMinUiMetrics(): MinUiMetrics {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    return remember(config.screenHeightDp, config.screenWidthDp, density) {
        val w = config.screenWidthDp.toFloat()
        val h = config.screenHeightDp.toFloat()
        val row = minOf(h / 8f, w / 8f).coerceIn(MIN_ROW_DP, MAX_ROW_DP)
        with(density) {
            // dp -> sp through the density keeps text proportional to the rows regardless of
            // the system font scale, like MinUI's fixed layout.
            MinUiMetrics(
                rowHeight = row.dp,
                text = (row * 0.48f).dp.toSp(),
                smallText = (row * 0.32f).dp.toSp(),
                gutter = (row * 0.33f).dp,
                hintHeight = (row * 0.72f).dp,
                isPortrait = h > w,
            )
        }
    }
}
