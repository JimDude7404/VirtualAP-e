package com.virtualap.app.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Simplified PreferencesManager for VirtualAP.
 * Singleton pattern with double-checked locking for thread safety.
 */
class PreferencesManager private constructor(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences(
        Constants.PREFS_NAME,
        Context.MODE_PRIVATE
    )

    var rootAvailable: Boolean
        get() = prefs.getBoolean(Constants.KEY_ROOT_AVAILABLE, false)
        set(value) {
            prefs.edit().putBoolean(Constants.KEY_ROOT_AVAILABLE, value).apply()
        }

    var isInstalled: Boolean
        get() = prefs.getBoolean(Constants.KEY_INSTALLED, false)
        set(value) {
            prefs.edit().putBoolean(Constants.KEY_INSTALLED, value).apply()
        }

    var followSystemTheme: Boolean
        get() = prefs.getBoolean(Constants.KEY_FOLLOW_SYSTEM_THEME, true)
        set(value) {
            prefs.edit().putBoolean(Constants.KEY_FOLLOW_SYSTEM_THEME, value).apply()
        }

    var darkTheme: Boolean
        get() = prefs.getBoolean(Constants.KEY_DARK_THEME, false)
        set(value) {
            prefs.edit().putBoolean(Constants.KEY_DARK_THEME, value).apply()
        }

    var amoledMode: Boolean
        get() = prefs.getBoolean(Constants.KEY_AMOLED_MODE, false)
        set(value) {
            prefs.edit().putBoolean(Constants.KEY_AMOLED_MODE, value).apply()
        }

    var useDynamicColor: Boolean
        get() = prefs.getBoolean(Constants.KEY_USE_DYNAMIC_COLOR, true)
        set(value) {
            prefs.edit().putBoolean(Constants.KEY_USE_DYNAMIC_COLOR, value).apply()
        }

    var themePalette: String
        get() = prefs.getString(Constants.KEY_THEME_PALETTE, "CATPPUCCIN") ?: "CATPPUCCIN"
        set(value) {
            prefs.edit().putString(Constants.KEY_THEME_PALETTE, value).apply()
        }

    companion object {
        @Volatile
        private var INSTANCE: PreferencesManager? = null

        @JvmStatic
        fun getInstance(context: Context): PreferencesManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PreferencesManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
