package com.virtualap.app.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.virtualap.app.ui.theme.ThemePalette
import com.virtualap.app.util.APManager
import com.virtualap.app.util.PreferencesManager
import com.virtualap.app.util.RootChecker
import com.virtualap.app.util.RootStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class InstallStatus { Checking, NotInstalled, Installing, Installed, Error }

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = PreferencesManager.getInstance(application)

    var rootStatus by mutableStateOf(
        if (prefs.rootAvailable) RootStatus.Granted else RootStatus.Checking
    )
        private set

    var installStatus by mutableStateOf(InstallStatus.Checking)
        private set

    // Private mutable backing state — read via computed val below to avoid
    // JVM setter name clash with the explicit set* functions.
    private var _followSystemTheme by mutableStateOf(prefs.followSystemTheme)
    private var _darkThemeEnabled  by mutableStateOf(prefs.darkTheme)
    private var _dynamicColor      by mutableStateOf(prefs.useDynamicColor)
    private var _amoledMode        by mutableStateOf(prefs.amoledMode)
    private var _themePalette      by mutableStateOf(ThemePalette.fromName(prefs.themePalette))

    val followSystemTheme: Boolean get() = _followSystemTheme
    val darkThemeEnabled:  Boolean get() = _darkThemeEnabled
    val dynamicColor:      Boolean get() = _dynamicColor
    val amoledMode:        Boolean get() = _amoledMode
    val themePalette:      ThemePalette get() = _themePalette

    fun setFollowSystemTheme(v: Boolean) { _followSystemTheme = v; prefs.followSystemTheme = v }
    fun setDarkTheme(v: Boolean)         { _darkThemeEnabled  = v; prefs.darkTheme = v }
    fun setDynamicColor(v: Boolean)      { _dynamicColor      = v; prefs.useDynamicColor = v }
    fun setAmoledMode(v: Boolean)        { _amoledMode        = v; prefs.amoledMode = v }
    fun setThemePalette(p: ThemePalette) { _themePalette      = p; prefs.themePalette = p.name }

    init {
        if (prefs.rootAvailable) {
            checkInstalled()
        } else {
            checkRoot()
        }
    }

    fun checkRoot() {
        viewModelScope.launch {
            rootStatus = RootChecker.checkRootAccess()
            prefs.rootAvailable = rootStatus == RootStatus.Granted
            if (rootStatus == RootStatus.Granted) checkInstalled()
        }
    }

    fun checkInstalled() {
        viewModelScope.launch {
            installStatus = InstallStatus.Checking
            val installed = withContext(Dispatchers.IO) { APManager.isInstalled() }
            installStatus = if (installed) InstallStatus.Installed else InstallStatus.NotInstalled
        }
    }

    fun markInstalled() {
        installStatus = InstallStatus.Installed
        prefs.isInstalled = true
    }
}
