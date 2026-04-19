package com.furkn.vpn

import android.content.Context

object SplitTunnelPrefs {

    fun loadExcludedPackages(context: Context): List<String> {
        val prefs = context.getSharedPreferences("furkn_prefs", Context.MODE_PRIVATE)

        return prefs.getStringSet("excluded_packages", emptySet())
            ?.toList()
            ?.filter { it.isNotBlank() }
            ?.sorted()
            ?: emptyList()
    }
}