package app.gamenative.runtime

import androidx.annotation.VisibleForTesting
import timber.log.Timber

// testable dispatch seam -- pulled out of the compose collector so unit tests don't need navhost.
// takes sealed GameRuntime (not a raw string) so a new variant forces a compile error here.
// callers resolve container.runtime via GameRuntime.fromId(...); see GameRuntime for the
// unknown-string / back-compat fallback rationale.
@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
fun dispatchLaunchByRuntime(
    runtime: GameRuntime,
    appId: String,
    navigateToWine: () -> Unit,
    navigateToWebView: () -> Unit,
) {
    when (runtime) {
        WineRuntime -> navigateToWine()
        WebViewRuntime -> {
            Timber.i("html5 runtime dispatched for app $appId — navigating to WebViewScreen")
            navigateToWebView()
        }
    }
}

// exit-side counterpart to dispatchLaunchByRuntime. XServerScreen and WebViewScreen pass
// this as their navigateBack callback. expectedRoute guards against acting on a back that
// fired after the user already navigated elsewhere; the route equality check mirrors the
// in-line block this replaced. we ALWAYS pop back to the library, regardless of how the
// session was launched -- finish() on an external-intent launch (home-screen shortcut /
// deep link) would close the whole app instead of returning to the library.
//
// finishActivity / wasLaunchedViaExternalIntent are retained in the signature for the
// call sites + test seam even though the body no longer finishes; popBackStack is passed
// as a lambda so the function stays NavController-free for unit testability.
@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
fun dispatchNavigateBack(
    expectedRoute: String,
    currentRoute: String?,
    wasLaunchedViaExternalIntent: Boolean,
    finishActivity: () -> Unit,
    popBackStack: () -> Unit,
    clearExternalIntentFlag: () -> Unit,
) {
    if (currentRoute != expectedRoute) return
    // Always pop back to the library. clear the external-intent flag first so a later
    // genuine back doesn't mis-fire on a stale flag.
    clearExternalIntentFlag()
    popBackStack()
}
