package app.gamenative.ui.screen.minimal

import android.view.KeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent

/**
 * Gamepad/keyboard mapping for MinUI screens. Acts on key-down only; returns true (consumed)
 * for any key it has a handler for.
 */
fun Modifier.minUiKeys(
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onPageUp: (() -> Unit)? = null,
    onPageDown: (() -> Unit)? = null,
    onA: (() -> Unit)? = null,
    onB: (() -> Unit)? = null,
    onY: (() -> Unit)? = null,
    onStart: (() -> Unit)? = null,
    onX: (() -> Unit)? = null,
): Modifier = onPreviewKeyEvent { event ->
    val native = event.nativeKeyEvent
    val handler = when (native.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> onUp
        KeyEvent.KEYCODE_DPAD_DOWN -> onDown
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> onPageUp
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN -> onPageDown
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> onA
        KeyEvent.KEYCODE_BUTTON_B -> onB
        KeyEvent.KEYCODE_BUTTON_X -> onX
        KeyEvent.KEYCODE_BUTTON_Y -> onY
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> onStart
        else -> null
    } ?: return@onPreviewKeyEvent false
    // Swallow the matching key-up too so it doesn't leak to other handlers.
    if (native.action == KeyEvent.ACTION_DOWN) handler()
    true
}
