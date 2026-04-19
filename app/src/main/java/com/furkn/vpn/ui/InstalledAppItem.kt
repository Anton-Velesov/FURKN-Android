package com.furkn.vpn.ui

data class InstalledAppItem(
    val label: String,
    val packageName: String,
    val isChecked: Boolean = false,
    val isSuggested: Boolean = false
)