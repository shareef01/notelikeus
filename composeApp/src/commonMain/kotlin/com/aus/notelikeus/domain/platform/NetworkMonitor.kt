package com.aus.notelikeus.domain.platform

import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the device has a network to sync over.
 *
 * Only Android provides one (ConnectivityManager's default-network callback). Where none is bound,
 * callers treat the device as online, which is what they assumed before this existed.
 */
interface NetworkMonitor {
    val isOnline: StateFlow<Boolean>
}
