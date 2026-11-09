package app.gamenative.utils

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import app.gamenative.BuildConfig
import timber.log.Timber

/**
 * modernMinimal: opt-in "use GameNative as the home screen". The HOME entry is the
 * `.MainActivityHome` activity-alias in src/modernMinimal/AndroidManifest.xml, disabled by
 * default. Enabling it only makes GameNative *eligible*; Android requires the user to pick it as
 * the default home app, so [setEnabled] then opens the system home-app chooser.
 */
object HomeScreenSwitcher {
    private const val ALIAS = "app.gamenative.MainActivityHome"

    /** Only the minimal build declares the alias. */
    val isSupported: Boolean get() = BuildConfig.MINIMAL_UI

    private fun alias(context: Context) = ComponentName(context, ALIAS)

    /** Whether the HOME alias is enabled (GameNative is offered as a home app). */
    fun isEnabled(context: Context): Boolean {
        if (!isSupported) return false
        return context.packageManager.getComponentEnabledSetting(alias(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }

    /** Whether GameNative is currently the default home app. */
    fun isDefaultHome(context: Context): Boolean {
        if (!isSupported) return false
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == context.packageName
    }

    /**
     * Enables or disables the HOME alias, then opens the system home-app chooser so the user can
     * pick (or leave) GameNative. Disabling makes Android fall back to another home app.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        if (!isSupported) return
        context.packageManager.setComponentEnabledSetting(
            alias(context),
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
        if (enabled) openHomeSettings(context)
    }

    /** System "Home app" picker, with fallbacks for ROMs that don't expose it directly. */
    fun openHomeSettings(context: Context) {
        val candidates = listOf(
            Settings.ACTION_HOME_SETTINGS,
            Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            Settings.ACTION_SETTINGS,
        )
        for (action in candidates) {
            try {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                Timber.w("HomeScreenSwitcher: %s not available", action)
            }
        }
    }
}
