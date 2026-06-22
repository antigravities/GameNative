package app.gamenative.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.utils.GameSessionTimer
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.delay

/**
 * Full-screen darkening veil shared by the in-game suspend overlays (no Quick Menu) and the
 * backdrop behind the Quick Menu. Optionally shows the game's logo (bottom-right) and the
 * session/total play-time counter (top-right); both are suppressed via [showInfo] (e.g. the
 * Screen Effects tab, which previews effects on the live frame). [content] is centered — the
 * suspend overlays put their Resume / Open-Quick-Menu buttons there; the Quick Menu leaves it
 * empty (its panel provides the actions).
 */
@Composable
fun SuspendBackdrop(
    logoUrl: String,
    gameName: String,
    modifier: Modifier = Modifier,
    veilAlpha: Float = 0.5f,
    showInfo: Boolean = true,
    immersive: Boolean = false,
    onClick: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = veilAlpha))
            .then(
                // Immersive only: .clickable() adds a screen-sized focus target that Compose's
                // directional focus search can land on, silently closing the menu on the next
                // DPAD_CENTER. Flat mode keeps the plain clickable.
                if (immersive) {
                    Modifier.pointerInput(Unit) { detectTapGestures { onClick() } }
                } else {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
        if (showInfo) {
            InGameTimeCounter(modifier = Modifier.align(Alignment.TopEnd))
            SuspendArtworkCorner(
                logoUrl = logoUrl,
                gameName = gameName,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp, end = 16.dp),
            )
        }
    }
}

/**
 * Game logo art (or title text fallback) shown in a corner of the suspend backdrop. Reuses the
 * same image source + Landscapist pattern as the booting splash (BootingSplash.kt).
 */
@Composable
fun SuspendArtworkCorner(logoUrl: String, gameName: String, modifier: Modifier = Modifier) {
    if (logoUrl.isEmpty() && gameName.isEmpty()) return
    val fallbackText: @Composable () -> Unit = {
        Text(
            text = gameName,
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
    Box(modifier = modifier) {
        if (logoUrl.isEmpty()) {
            fallbackText()
        } else {
            CoilImage(
                modifier = Modifier
                    .heightIn(max = 72.dp)
                    .widthIn(max = 220.dp),
                imageModel = { logoUrl },
                imageOptions = ImageOptions(
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomEnd,
                    contentDescription = gameName,
                ),
                loading = {},
                failure = { fallbackText() },
            )
        }
    }
}

/**
 * Small floating overlay showing how long the user has actively been in-game this session and
 * in total (lifetime). Reads [GameSessionTimer] directly and re-reads once a second so the value
 * updates. Visibility is controlled by its caller ([SuspendBackdrop]); while suspended the timer
 * is frozen so the displayed value simply holds.
 */
@Composable
private fun InGameTimeCounter(modifier: Modifier = Modifier) {
    // A counter bumped once per second to recompose the displayed strings.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            tick++
            delay(1000L)
        }
    }

    // Reference `tick` so this recomposes on each tick.
    @Suppress("UNUSED_EXPRESSION") tick
    val sessionText = formatPlayTime(GameSessionTimer.currentSessionMs())
    val totalText = formatPlayTime(GameSessionTimer.totalMs())

    Surface(
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 16.dp, end = 16.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            PlayTimeRow(
                label = stringResource(R.string.quick_menu_session_time),
                value = sessionText,
            )
            Spacer(modifier = Modifier.height(2.dp))
            PlayTimeRow(
                label = stringResource(R.string.quick_menu_total_time),
                value = totalText,
            )
        }
    }
}

@Composable
private fun PlayTimeRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Rounds to the nearest minute, then formats as `Xm` under an hour, `Hh MMm` otherwise. */
private fun formatPlayTime(ms: Long): String {
    val totalMinutes = ((ms + 30_000L) / 60_000L).coerceAtLeast(0L)  // round to nearest minute
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) {
        "%dh %02dm".format(hours, minutes)   // e.g. 3h 04m
    } else {
        "%dm".format(minutes)                // e.g. 12m
    }
}
