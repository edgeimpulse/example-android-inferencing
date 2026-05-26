package com.edgeimpulse.gattsensors

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Compose helper that mirrors the device's "internet-validated" connectivity
 * state into a [State]<Boolean>. Updates on every transition between
 * online / offline so UIs can flip to local-only fallbacks without polling.
 *
 * Returns true when the active network is capable of reaching the public
 * internet (NET_CAPABILITY_VALIDATED) — captive portals and metered links
 * that have not yet validated are reported as offline so we don't lose
 * samples to silently-failing uploads.
 */
@Composable
fun rememberOnline(): State<Boolean> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(isCurrentlyOnline(context)) }
    DisposableEffect(context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { state.value = isCurrentlyOnline(context) }
            override fun onLost(network: Network)      { state.value = isCurrentlyOnline(context) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                state.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                              caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try { cm?.registerNetworkCallback(request, callback) } catch (_: SecurityException) { }
        onDispose { try { cm?.unregisterNetworkCallback(callback) } catch (_: Exception) { } }
    }
    return state
}

private fun isCurrentlyOnline(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
           caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
