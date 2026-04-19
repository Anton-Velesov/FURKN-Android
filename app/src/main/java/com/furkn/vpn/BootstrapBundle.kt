package com.furkn.vpn

data class BootstrapBundle(
    val session: BootstrapSession,
    val selectorPolicy: BootstrapSelectorPolicy,
    val userAccess: BootstrapUserAccess,
    val entries: List<BootstrapEntry>
)

data class BootstrapSession(
    val configTtlSec: Int
)

data class BootstrapSelectorPolicy(
    val strategy: String,
    val connectTimeoutMs: Int,
    val handshakeTimeoutMs: Int,
    val failureCooldownSec: Int,
    val retrySameEntryCount: Int,
    val preferUdpWhenAvailable: Boolean,
    val fallbackToTcpOnUdpFailure: Boolean
)

data class BootstrapUserAccess(
    val subscriptionStatus: String,
    val userId: String,
    val planCode: String,
    val expiresAt: String,
    val phone: String?,
    val firstName: String?,
    val lastName: String?
)

data class BootstrapEntry(
    val entryId: String,
    val host: String,
    val port: Int,
    val score: Int,
    val region: String,
    val transports: List<BootstrapEntryTransport>
)

data class BootstrapEntryTransport(
    val code: String,
    val priority: Int,
    val networkType: String,
    val antiDpiClass: String,
    val provisioningStatus: String,
    val connectMaterial: BootstrapConnectMaterial
)

data class BootstrapConnectMaterial(
    val auth: String? = null,
    val clientId: String? = null,
    val obfsType: String? = null,
    val obfsPassword: String? = null
)