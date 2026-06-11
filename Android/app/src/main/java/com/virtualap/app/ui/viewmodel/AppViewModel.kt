package com.virtualap.app.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
