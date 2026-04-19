package com.furkn.vpn.ui

import com.furkn.vpn.api.BootstrapEntry

data class AppState(
    val currentScreen: String = "login",
    val phone: String = "",
    val code: String = "",
    val loginToken: String? = null,

    val selectedTransport: String? = null,
    val selectedHost: String? = null,
    val selectedPort: Int? = null,
    val selectedEntryId: String? = null,
    val selectedAuth: String? = null,

    val isConnected: Boolean = false,
    val isLoading: Boolean = false,
    val message: String? = null,
    val bootstrapEntries: List<BootstrapEntry> = emptyList(),

    val excludedPackages: List<String> = emptyList(),
    val installedApps: List<InstalledAppItem> = emptyList(),
    val showAllApps: Boolean = false
)