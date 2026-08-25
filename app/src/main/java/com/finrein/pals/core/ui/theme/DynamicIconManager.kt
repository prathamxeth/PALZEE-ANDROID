package com.finrein.pals.core.ui.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.util.Log

object DynamicIconManager {
    private const val TAG = "DynamicIconManager"
    
    private const val LIGHT_ALIAS = "com.finrein.pals.MainActivityLight"
    private const val DARK_ALIAS = "com.finrein.pals.MainActivityDark"

    /**
     * Checks the current system UI Mode (Light / Dark) and syncs the app icon accordingly.
     */
    fun syncWithSystemTheme(context: Context) {
        val isDarkMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        setIconTheme(context, isDark = isDarkMode)
    }

    /**
     * Toggles between the Light and Dark launcher icon aliases via PackageManager.
     */
    fun setIconTheme(context: Context, isDark: Boolean) {
        try {
            val pm = context.packageManager
            val lightComponent = ComponentName(context.packageName, LIGHT_ALIAS)
            val darkComponent = ComponentName(context.packageName, DARK_ALIAS)

            val currentLightState = pm.getComponentEnabledSetting(lightComponent)
            val isCurrentlyDark = currentLightState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED

            if (isCurrentlyDark == isDark) {
                // Already in desired state
                return
            }

            Log.d(TAG, "Switching app icon to ${if (isDark) "Dark" else "Light"} mode")

            if (isDark) {
                // Enable dark first then disable light to avoid brief period with 0 launcher activities
                pm.setComponentEnabledSetting(
                    darkComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    lightComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            } else {
                pm.setComponentEnabledSetting(
                    lightComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    darkComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch dynamic app icon", e)
        }
    }
}
