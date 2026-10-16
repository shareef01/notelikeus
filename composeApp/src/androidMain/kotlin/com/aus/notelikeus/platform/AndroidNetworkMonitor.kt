package com.aus.notelikeus.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.aus.notelikeus.domain.platform.NetworkMonitor
import com.aus.notelikeus.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "AndroidNetworkMonitor"

/**
 * Follows the default network for the life of the process (it is a Koin single, so the callback is
 * never unregistered). Needs ACCESS_NETWORK_STATE, which WorkManager's manifest already merges in
 * for SyncWorker's CONNECTED constraint.
 */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    private val _isOnline = MutableStateFlow(connectivityManager?.activeNetwork != null)
    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    /** The default network the last onAvailable named; a loss of any other one is not ours. */
    @Volatile
    private var current: Network? = null

    init {
        runCatching {
            connectivityManager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    current = network
                    _isOnline.value = true
                }

                override fun onLost(network: Network) {
                    if (current == null || current == network) {
                        current = null
                        _isOnline.value = false
                    }
                }
            })
        }.onFailure {
            // Nothing will ever report a change, so a stale "offline" would stick for good.
            _isOnline.value = true
            AppLog.warn(TAG, "Network callback unavailable; assuming online", it)
        }
    }
}
