package com.furkn.vpn

data class VpnProfile(
    val id: String,
    val type: String, // "hy2" / "reality"
    val host: String,
    val port: Int,
    val auth: String,
    val priority: Int
)