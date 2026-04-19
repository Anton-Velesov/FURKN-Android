package com.furkn.vpn

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

object BootstrapParser {

    private val gson = Gson()

    fun parse(json: String): BootstrapBundle {
        val raw = gson.fromJson(json, RawBootstrapBundle::class.java)

        return BootstrapBundle(
            session = BootstrapSession(
                configTtlSec = raw.session.configTtlSec
            ),
            selectorPolicy = BootstrapSelectorPolicy(
                strategy = raw.policy.selectorPolicy.strategy,
                connectTimeoutMs = raw.policy.selectorPolicy.connectTimeoutMs,
                handshakeTimeoutMs = raw.policy.selectorPolicy.handshakeTimeoutMs,
                failureCooldownSec = raw.policy.selectorPolicy.failureCooldownSec,
                retrySameEntryCount = raw.policy.selectorPolicy.retrySameEntryCount,
                preferUdpWhenAvailable = raw.policy.selectorPolicy.preferUdpWhenAvailable,
                fallbackToTcpOnUdpFailure = raw.policy.selectorPolicy.fallbackToTcpOnUdpFailure
            ),
            userAccess = BootstrapUserAccess(
                subscriptionStatus = raw.userAccess.subscriptionStatus,
                userId = raw.userAccess.userId,
                planCode = raw.userAccess.planCode,
                expiresAt = raw.userAccess.expiresAt ?: "",
                phone = raw.userAccess.phone,
                firstName = raw.userAccess.firstName,
                lastName = raw.userAccess.lastName
            ),
            entries = raw.entries.map { entry ->
                BootstrapEntry(
                    entryId = entry.entryId ?: "backend-entry",
                    host = entry.host,
                    port = entry.port,
                    score = entry.score ?: 0,
                    region = entry.region ?: "",
                    transports = entry.transports.map { t ->
                        BootstrapEntryTransport(
                            code = t.code,
                            priority = t.priority ?: 0,
                            networkType = t.networkType ?: "",
                            antiDpiClass = t.antiDpiClass ?: "",
                            provisioningStatus = t.provisioningStatus ?: "",
                            connectMaterial = BootstrapConnectMaterial(
                                auth = t.connectMaterial.auth,
                                clientId = t.connectMaterial.clientId,
                                obfsType = t.connectMaterial.obfsType,
                                obfsPassword = t.connectMaterial.obfsPassword
                            )
                        )
                    }
                )
            }
        )
    }

    // RAW classes (под snake_case JSON)

    private data class RawBootstrapBundle(
        val session: RawSession,
        val policy: RawPolicy,
        @SerializedName("user_access") val userAccess: RawUserAccess,
        val entries: List<RawEntry>
    )

    private data class RawPolicy(
        @SerializedName("selector_policy") val selectorPolicy: RawSelectorPolicy
    )

    private data class RawSession(
        @SerializedName("config_ttl_sec") val configTtlSec: Int
    )

    private data class RawSelectorPolicy(
        val strategy: String,
        @SerializedName("connect_timeout_ms") val connectTimeoutMs: Int,
        @SerializedName("handshake_timeout_ms") val handshakeTimeoutMs: Int,
        @SerializedName("failure_cooldown_sec") val failureCooldownSec: Int,
        @SerializedName("retry_same_entry_count") val retrySameEntryCount: Int,
        @SerializedName("prefer_udp_when_available") val preferUdpWhenAvailable: Boolean,
        @SerializedName("fallback_to_tcp_on_udp_failure") val fallbackToTcpOnUdpFailure: Boolean
    )

    private data class RawUserAccess(
        @SerializedName("subscription_status") val subscriptionStatus: String,
        @SerializedName("user_id") val userId: String,
        @SerializedName("plan_code") val planCode: String,
        @SerializedName("expires_at") val expiresAt: String?,
        val phone: String?,
        @SerializedName("first_name") val firstName: String?,
        @SerializedName("last_name") val lastName: String?
    )

    private data class RawEntry(
        @SerializedName("entry_id") val entryId: String?,
        val host: String,
        val port: Int,
        val score: Int?,
        val region: String?,
        val transports: List<RawTransport>
    )

    private data class RawTransport(
        val code: String,
        val priority: Int?,
        @SerializedName("network_type") val networkType: String?,
        @SerializedName("anti_dpi_class") val antiDpiClass: String?,
        @SerializedName("provisioning_status") val provisioningStatus: String?,
        @SerializedName("connect_material") val connectMaterial: RawConnectMaterial
    )

    private data class RawConnectMaterial(
        val auth: String?,
        @SerializedName("client_id") val clientId: String?,
        @SerializedName("obfs_type") val obfsType: String?,
        @SerializedName("obfs_password") val obfsPassword: String?
    )
}