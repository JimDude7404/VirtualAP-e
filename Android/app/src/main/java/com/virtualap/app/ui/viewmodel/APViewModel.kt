package com.virtualap.app.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.snapshotFlow
import com.virtualap.app.util.APManager
import com.virtualap.app.util.APStatus
import com.virtualap.app.util.DHCPLease
import com.virtualap.app.util.NetworkIface
import com.virtualap.app.util.PreferencesManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class APConfig(
    val ssid: String = "",
    val password: String = "",
    val band: String = "2",
    val channel: String = "",
    val upstream: String = "auto"
)

class APViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = PreferencesManager.getInstance(application)

    var status by mutableStateOf(APStatus())
        private set
    var config by mutableStateOf(
        APConfig(
            ssid = prefs.apSsid,
            password = prefs.apPassword,
            band = prefs.apBand,
            channel = prefs.apChannel,
            upstream = prefs.apUpstream
        )
    )
    var leases by mutableStateOf<List<DHCPLease>>(emptyList())
        private set
    var interfaces by mutableStateOf<List<NetworkIface>>(emptyList())
        private set
    var isStarting by mutableStateOf(false)
        private set
    var isStopping by mutableStateOf(false)
        private set
    var logText by mutableStateOf("")
        private set
    val actionLogs = mutableStateListOf<Pair<Int, String>>()
    var showActionLogs by mutableStateOf(false)
        private set
    var bootEnabled by mutableStateOf(false)

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            snapshotFlow { config }.collect { cfg ->
                prefs.apSsid = cfg.ssid
                prefs.apPassword = cfg.password
                prefs.apBand = cfg.band
                prefs.apChannel = cfg.channel
                prefs.apUpstream = cfg.upstream
            }
        }
        startPolling()
        loadBootFlag()
        loadInterfaces()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                refreshStatus()
                if (status.running) refreshLeases()
                refreshLog()
                delay(3000)
            }
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            val s = APManager.getStatus()
            status = s
        }
    }

    private fun refreshLeases() {
        viewModelScope.launch {
            leases = APManager.getLeases()
        }
    }

    fun loadInterfaces() {
        viewModelScope.launch {
            interfaces = APManager.getInterfaces()
        }
    }

    private fun refreshLog() {
        viewModelScope.launch {
            logText = APManager.readLog()
        }
    }

    fun start() {
        val cfg = config
        if (cfg.ssid.isBlank() || cfg.password.length < 8) return
        viewModelScope.launch {
            isStarting = true
            showActionLogs = true
            actionLogs.clear()
            APManager.start(cfg.ssid, cfg.password, cfg.upstream, cfg.band, cfg.channel.takeIf { it.isNotBlank() }) { level, msg ->
                actionLogs.add(level to msg)
            }
            delay(500)
            refreshStatus()
            isStarting = false
        }
    }

    fun stop() {
        viewModelScope.launch {
            isStopping = true
            showActionLogs = true
            actionLogs.clear()
            APManager.stop { level, msg -> actionLogs.add(level to msg) }
            delay(500)
            refreshStatus()
            isStopping = false
        }
    }

    fun clearLog() {
        viewModelScope.launch {
            APManager.clearLog()
            logText = ""
        }
    }

    fun dismissActionLogs() { showActionLogs = false }

    private fun loadBootFlag() {
        viewModelScope.launch {
            bootEnabled = APManager.getBootFlag()
        }
    }

    fun setBootFlag(enabled: Boolean) {
        bootEnabled = enabled
        viewModelScope.launch { APManager.setBootFlag(enabled) }
    }
}
