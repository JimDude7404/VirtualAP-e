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
import com.virtualap.app.util.ApCaps
import com.virtualap.app.util.NetworkIface
import com.virtualap.app.util.PreferencesManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class APConfig(
    val ssid: String = "",
    val password: String = "",
    val band: String = "2",
    val channel: String = "",
    val width: String = "auto",
    val upstream: String = "auto",
    val gateway: String = "192.168.42.1",
    val dnsServers: String = "",
    val hidden: Boolean = false,
    val containerMode: Boolean = false,
    val containerName: String = ""
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
            channel = validChannelForBand(prefs.apBand, prefs.apChannel),
            width = prefs.apWidth,
            upstream = prefs.apUpstream,
            gateway = prefs.apGateway,
            dnsServers = prefs.apDnsServers,
            hidden = prefs.apHidden,
            containerMode = prefs.apContainerMode,
            containerName = prefs.apContainer
        )
    )
    var interfaces by mutableStateOf<List<NetworkIface>>(emptyList())
        private set
    /** Chip channel-width capabilities (5GHz); drives width-option greying. */
    var caps by mutableStateOf(ApCaps())
        private set
    /** Running Droidspaces containers; empty = hide the integration UI entirely. */
    var containers by mutableStateOf<List<String>>(emptyList())
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
    /** False until the first status/interfaces/containers fetch completes, so
     *  the UI can show a spinner instead of flashing stale "stopped" state. */
    var isReady by mutableStateOf(false)
        private set

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            snapshotFlow { config }.collect { cfg ->
                prefs.apSsid = cfg.ssid
                prefs.apPassword = cfg.password
                prefs.apBand = cfg.band
                prefs.apChannel = cfg.channel
                prefs.apWidth = cfg.width
                prefs.apUpstream = cfg.upstream
                prefs.apGateway = cfg.gateway
                prefs.apDnsServers = cfg.dnsServers
                prefs.apHidden = cfg.hidden
                prefs.apContainerMode = cfg.containerMode
                prefs.apContainer = cfg.containerName
            }
        }
        // One parallel initial load — gate the UI on it so everything appears at
        // once (no status flicker, no late-popping container toggle).
        viewModelScope.launch {
            val s = async { APManager.getStatus() }
            val ifs = async { APManager.getInterfaces() }
            val cs = async { APManager.getContainers() }
            val cp = async { APManager.getCapabilities() }
            status = s.await()
            applyInterfaceList(ifs.await())
            applyContainerList(cs.await())
            caps = cp.await()
            logText = APManager.readLog()
            isReady = true
            startPolling()
        }
    }

    private fun applyContainerList(list: List<String>) {
        containers = list
        // If the saved container vanished (stopped / Droidspaces gone), drop
        // managed mode so we never send a stale -K to the backend.
        val cfg = config
        if (cfg.containerMode && cfg.containerName !in list) {
            config = cfg.copy(containerMode = false, containerName = "")
        }
    }

    private fun applyInterfaceList(ifaces: List<NetworkIface>) {
        interfaces = ifaces
        // If the saved upstream is gone (e.g. WireGuard tunnel stopped), reset to
        // auto so a stale iface name isn't sent to the backend.
        if (config.upstream != "auto" && ifaces.none { it.name == config.upstream }) {
            config = config.copy(upstream = "auto")
        }
    }

    fun loadContainers() {
        viewModelScope.launch { applyContainerList(APManager.getContainers()) }
    }

    /** Pull-to-refresh: re-fetch status, interfaces, containers and log in
     *  parallel and suspend until they all land (so the spinner reflects real
     *  work). Root status is refreshed separately by the caller. */
    suspend fun refreshAllNow() = coroutineScope {
        val s = async { APManager.getStatus() }
        val ifs = async { APManager.getInterfaces() }
        val cs = async { APManager.getContainers() }
        status = s.await()
        applyInterfaceList(ifs.await())
        applyContainerList(cs.await())
        logText = APManager.readLog()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(3000)   // initial state already loaded; poll afterwards
                refreshStatus()
                refreshLog()
            }
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            val s = APManager.getStatus()
            status = s
        }
    }

    fun loadInterfaces() {
        viewModelScope.launch { applyInterfaceList(APManager.getInterfaces()) }
    }

    /**
     * Whether a width option is selectable for the current band + selected
     * channel + chip caps. 2.4GHz is fixed to 20MHz (only Auto/20). On 5GHz,
     * 40 needs HT40 and 80 needs VHT, AND the chosen channel must sit in a
     * 40/80MHz block (e.g. channel 165 is 20MHz-only). Auto/20 always allowed.
     */
    fun widthEnabled(value: String): Boolean =
        widthSupported(config.band, config.channel, value)

    private fun widthSupported(band: String, channel: String, width: String): Boolean =
        when (width) {
            "auto", "20" -> true
            "40" -> band == "5" && caps.ht40 && channelSupportsWide(channel)
            "80" -> band == "5" && caps.vht && channelSupportsWide(channel)
            else -> false
        }

    /**
     * True if a 5GHz channel can carry a 40/80MHz block (mirrors the backend's
     * vht_seg0). Blank = "Auto": the backend picks a wide-capable channel, so
     * allow wide widths. 20MHz-only channels like 165 return false.
     */
    private fun channelSupportsWide(channel: String): Boolean {
        if (channel.isBlank()) return true
        val ch = channel.toIntOrNull() ?: return false
        return ch in 36..48 || ch in 52..64 || ch in 100..112 ||
            ch in 116..128 || ch in 132..144 || ch in 149..161
    }

    /** Switch band: channel + width options differ per band, so reset both. */
    fun selectBand(value: String) {
        config = config.copy(band = value, channel = "", width = "auto")
    }

    /** Switch channel, falling back to Auto width if it can't carry the current width. */
    fun selectChannel(value: String) {
        val next = config.copy(channel = value)
        config = if (widthSupported(next.band, value, next.width)) next
                 else next.copy(width = "auto")
    }

    fun selectWidth(value: String) {
        config = config.copy(width = value)
    }

    private fun refreshLog() {
        viewModelScope.launch {
            logText = APManager.readLog()
        }
    }

    fun start() {
        val cfg = config
        if (cfg.ssid.isBlank() || cfg.password.length < 8) return
        if (cfg.containerMode && cfg.containerName.isBlank()) return
        viewModelScope.launch {
            isStarting = true
            actionLogs.clear()
            logText = ""
            showActionLogs = true
            APManager.start(
                cfg.ssid, cfg.password, cfg.upstream, cfg.band,
                cfg.channel.takeIf { it.isNotBlank() },
                cfg.width,
                cfg.gateway, cfg.dnsServers.takeIf { it.isNotBlank() },
                cfg.hidden,
                if (cfg.containerMode) cfg.containerName else ""
            ) { level, msg ->
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
            actionLogs.clear()
            logText = ""
            showActionLogs = true
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
            actionLogs.clear()
        }
    }

    fun openLogSheet() { showActionLogs = true }
    fun dismissActionLogs() { showActionLogs = false }

    companion object {
        // Must be companion (not instance method) — called from property initializer
        // before the instance exists.
        fun validChannelForBand(band: String, channel: String): String {
            if (channel.isBlank()) return ""
            val valid = if (band == "5") {
                setOf("36", "40", "44", "48", "149", "153", "157", "161", "165")
            } else {
                (1..11).map { "$it" }.toSet()
            }
            return if (channel in valid) channel else ""
        }
    }
}
