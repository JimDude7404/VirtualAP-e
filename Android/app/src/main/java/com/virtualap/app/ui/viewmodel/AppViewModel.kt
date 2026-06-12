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
import com.virtualap.app.util.VirtualAPInstaller
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

    // Set to true when a background root check fails after the user has already
    // passed the root check screen. Drives the revoked-root overlay in MainActivity.
    var rootRevokedInBackground by mutableStateOf(false)
        private set

    // Private mutable backing state — read via computed val to avoid JVM setter clash.
    private var _followSystemTheme by mutableStateOf(prefs.followSystemTheme)
    private var _darkThemeEnabled  by mutableStateOf(prefs.darkTheme)
    private var _dynamicColor      by mutableStateOf(prefs.useDynamicColor)
    private var _amoledMode        by mutableStateOf(prefs.amoledMode)
    private var _themePalette      by mutableStateOf(ThemePalette.fromName(prefs.themePalette))

    val followSystemTheme: Boolean    get() = _followSystemTheme
    val darkThemeEnabled:  Boolean    get() = _darkThemeEnabled
    val dynamicColor:      Boolean    get() = _dynamicColor
    val amoledMode:        Boolean    get() = _amoledMode
    val themePalette:      ThemePalette get() = _themePalette

    fun setFollowSystemTheme(v: Boolean) { _followSystemTheme = v; prefs.followSystemTheme = v }
    fun setDarkTheme(v: Boolean)         { _darkThemeEnabled  = v; prefs.darkTheme = v }
    fun setDynamicColor(v: Boolean)      { _dynamicColor      = v; prefs.useDynamicColor = v }
    fun setAmoledMode(v: Boolean)        { _amoledMode        = v; prefs.amoledMode = v }
    fun setThemePalette(p: ThemePalette) { _themePalette      = p; prefs.themePalette = p.name }

    init {
        if (prefs.hasSeenRootCheck && prefs.rootAvailable) {
            // Returning user: skip the root screen, jump straight to install check,
            // and verify root silently so we catch revocations without a prompt.
            checkInstalled()
            checkRootSilently()
        } else {
            // First launch or root previously denied: show RootCheckScreen and
            // immediately initiate the check so the SU manager dialog appears.
            checkRoot()
        }
    }

    /** Called from the "Grant Root Access" button on RootCheckScreen. */
    fun checkRoot() {
        viewModelScope.launch {
            rootStatus = RootStatus.Checking
            val status = withContext(Dispatchers.IO) { RootChecker.checkRootAccess() }
            rootStatus = status
            prefs.rootAvailable = status == RootStatus.Granted
            prefs.hasSeenRootCheck = true
            if (status == RootStatus.Granted) checkInstalled()
        }
    }

    /** Silent background re-verification for returning users. No UI unless it fails. */
    private fun checkRootSilently() {
        viewModelScope.launch(Dispatchers.IO) {
            val status = RootChecker.checkRootAccess()
            if (status != RootStatus.Granted) {
                prefs.rootAvailable = false
                withContext(Dispatchers.Main) {
                    rootStatus = RootStatus.Denied
                    rootRevokedInBackground = true
                }
            }
        }
    }

    /** Called from the "Retry" button in the revoked-root overlay. */
    fun retryRootAfterRevoke() {
        rootRevokedInBackground = false
        viewModelScope.launch {
            rootStatus = RootStatus.Checking
            val status = withContext(Dispatchers.IO) { RootChecker.checkRootAccess() }
            rootStatus = status
            prefs.rootAvailable = status == RootStatus.Granted
            if (status != RootStatus.Granted) rootRevokedInBackground = true
        }
    }

    /** On-demand root re-verification (e.g. pull-to-refresh on the main screen).
     *  Silent on success; surfaces the revoked overlay if access is gone. */
    fun recheckRoot() = checkRootSilently()

    fun checkInstalled() {
        viewModelScope.launch {
            installStatus = InstallStatus.Checking
            val app = getApplication<Application>()
            val installed = withContext(Dispatchers.IO) {
                // An outdated rootfs (APK update shipped a newer tarball) counts
                // as not-installed so the setup flow re-extracts it.
                APManager.isInstalled() && !VirtualAPInstaller.rootfsUpdateAvailable(app)
            }
            installStatus = if (installed) InstallStatus.Installed else InstallStatus.NotInstalled
        }
    }

    fun markInstalled() {
        installStatus = InstallStatus.Installed
        prefs.isInstalled = true
    }
}
