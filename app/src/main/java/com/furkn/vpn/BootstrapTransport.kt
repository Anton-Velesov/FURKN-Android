package com.furkn.vpn

data class BootstrapTransport(
    val code: String,
    val priority: Int,
    val networkType: String,
    val host: String,
    val port: Int,
    val auth: String? = null,
    val hy2ObfsType: String? = null,
    val hy2ObfsPassword: String? = null,
    val realityHost: String? = null,
    val realityPort: Int? = null,
    val realityUuid: String? = null,
    val realityPublicKey: String? = null,
    val realityShortId: String? = null,
    val realityServerName: String? = null
)