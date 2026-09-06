package com.sufyan.harness.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * §55 — real connectivity state, so the network-dependent parts of the app (OpenRouter, GitHub,
 * toolchain downloads) can say "you are offline" instead of failing with a socket error after a
 * timeout. Everything else — projects, the editor, local git history, the local runtime — keeps
 * working, which is why this is a plain observable and never a gate on the whole UI.
 *
 * The value is the OS's own answer (a validated internet-capable network), not a ping we invented.
 */
class Connectivity(private val context: Context) {

    private val manager: ConnectivityManager? =
        runCatching { context.getSystemService(ConnectivityManager::class.java) }.getOrNull()

    private val _online = MutableStateFlow(currentlyOnline())

    /** True when the system reports a validated network with internet capability. */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { _online.value = currentlyOnline() }
        override fun onLost(network: Network) { _online.value = currentlyOnline() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _online.value = currentlyOnline()
        }
    }

    /** Starts listening. Safe to call more than once; failures degrade to "assume online". */
    fun start() {
        val cm = manager ?: return
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback,
            )
        }
        _online.value = currentlyOnline()
    }

    fun stop() {
        runCatching { manager?.unregisterNetworkCallback(callback) }
    }

    /** A fresh read, for the moment just before a request is sent. */
    fun currentlyOnline(): Boolean {
        val cm = manager ?: return true // no ConnectivityManager: never block the user on a guess
        return runCatching {
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(true)
    }

    /**
     * The message a network feature shows when there is no connection. It names the feature and
     * says what still works, so it is never a dead end (RULE 4).
     */
    fun offlineReason(feature: String): String =
        "$feature needs an internet connection and this device is offline right now. " +
            "Projects, the editor, local git history and the local runtime keep working."
}
