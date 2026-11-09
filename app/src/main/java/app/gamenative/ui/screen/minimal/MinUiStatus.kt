package app.gamenative.ui.screen.minimal

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import android.text.format.DateFormat
import java.util.Date
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

private data class BatteryStatus(val fraction: Float, val charging: Boolean)

private fun readBattery(context: Context): BatteryStatus? {
    // Sticky broadcast: a null receiver just returns the last value, nothing stays registered.
    val intent: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return null
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return BatteryStatus(level.toFloat() / scale, charging)
}

/**
 * Shared MinUI top bar: [title] (dim, one line) or custom [content] on the left, battery pill on
 * the right. Every MinUI screen and overlay uses it so the battery sits in the same spot.
 */
@Composable
fun MinUiTopBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val m = rememberMinUiMetrics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(m.rowHeight)
            .padding(horizontal = m.gutter * 0.6f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.weight(1f).padding(horizontal = m.gutter * 0.4f),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    color = MinUiColors.Dim,
                    fontFamily = MinUiFont,
                    fontSize = m.smallText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                content?.invoke()
            }
        }
        MinUiClockPill()
        Spacer(Modifier.width(m.gutter * 0.3f))
        MinUiBatteryPill()
    }
}

/** Current time (system 12/24h setting) in a dark pill; ticks on each minute boundary. */
@Composable
fun MinUiClockPill(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val format = remember(context) { DateFormat.getTimeFormat(context) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            now = System.currentTimeMillis()
        }
    }
    val m = rememberMinUiMetrics()
    Box(
        modifier = modifier
            .height(m.hintHeight)
            .background(MinUiColors.HintPill, RoundedCornerShape(50))
            .padding(horizontal = m.hintHeight * 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = format.format(Date(now)),
            color = MinUiColors.Text,
            fontFamily = MinUiFont,
            fontSize = m.smallText,
            maxLines = 1,
        )
    }
}

/** MinUI-style top-right status: a dark circle with a battery glyph. */
@Composable
fun MinUiBatteryPill(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var battery by remember { mutableStateOf(readBattery(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            battery = readBattery(context)
        }
    }
    val b = battery ?: return
    val m = rememberMinUiMetrics()
    val diameter = m.hintHeight
    Box(
        modifier = modifier
            .size(diameter)
            .background(MinUiColors.HintPill, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(diameter * 0.5f)) {
            val bodyW = size.width * 0.86f
            val bodyH = size.height * 0.56f
            val top = (size.height - bodyH) / 2
            val stroke = size.width * 0.09f
            val radius = CornerRadius(stroke * 1.2f)
            // Body outline + terminal nub.
            drawRoundRect(MinUiColors.Text, Offset(0f, top), Size(bodyW, bodyH), radius, style = Stroke(stroke))
            drawRect(MinUiColors.Text, Offset(bodyW, top + bodyH * 0.3f), Size(size.width - bodyW, bodyH * 0.4f))
            // Fill proportional to charge.
            val inset = stroke * 1.6f
            val fillW = (bodyW - inset * 2) * b.fraction.coerceIn(0f, 1f)
            if (fillW > 0f) {
                drawRect(MinUiColors.Text, Offset(inset, top + inset), Size(fillW, bodyH - inset * 2))
            }
            if (b.charging) {
                // Small bolt, drawn in the background colour over the fill.
                val cx = bodyW / 2
                val bolt = Path().apply {
                    moveTo(cx + bodyH * 0.12f, top + bodyH * 0.12f)
                    lineTo(cx - bodyH * 0.22f, top + bodyH * 0.55f)
                    lineTo(cx, top + bodyH * 0.55f)
                    lineTo(cx - bodyH * 0.12f, top + bodyH * 0.88f)
                    lineTo(cx + bodyH * 0.22f, top + bodyH * 0.45f)
                    lineTo(cx, top + bodyH * 0.45f)
                    close()
                }
                drawPath(bolt, MinUiColors.HintPill)
            }
        }
    }
}
