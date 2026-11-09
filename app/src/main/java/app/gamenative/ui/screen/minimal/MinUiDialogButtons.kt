package app.gamenative.ui.screen.minimal

/** One button of a message dialog: its label and what it does ([T] is a handler, or a tag in tests). */
data class MinUiDialogButton<T : Any>(val label: String, val onClick: T)

/**
 * Which pad button runs which message-dialog button.
 * @property back B / system back; always the plain "close" request, never a choice.
 * @property showBackHint whether a B pill is drawn (B still works when it is not).
 */
data class MinUiDialogBindings<T : Any>(
    val a: MinUiDialogButton<T>?,
    val x: MinUiDialogButton<T>?,
    val y: MinUiDialogButton<T>?,
    val back: T?,
    val showBackHint: Boolean,
)

/**
 * Maps MessageDialog's confirm / dismiss / action buttons and its dismiss request onto
 * A / X / Y / B. The dismiss button can be a real choice (sync conflict "Keep local"
 * relaunches), so it never lands on B: with a confirm it goes on X, and as the only
 * button (an "OK") it goes on A.
 */
fun <T : Any> minUiDialogBindings(
    confirm: MinUiDialogButton<T>?,
    dismiss: MinUiDialogButton<T>?,
    action: MinUiDialogButton<T>?,
    dismissRequest: T?,
): MinUiDialogBindings<T> {
    val a = confirm ?: dismiss
    val x = if (confirm != null) dismiss else null
    return MinUiDialogBindings(
        a = a,
        x = x,
        y = action,
        back = dismissRequest,
        showBackHint = dismissRequest != null && x == null,
    )
}
