package com.furkn.vpn

object BootstrapMapper {

    private fun isTransportUsable(t: BootstrapEntryTransport): Boolean {
        return t.provisioningStatus.isBlank() || t.provisioningStatus == "active"
    }

    fun findPrimaryTransport(bundle: BootstrapBundle): BootstrapTransport? {
        val entry = bundle.entries.firstOrNull() ?: return null

        val hy2 = entry.transports
            .filter { isTransportUsable(it) }
            .firstOrNull { it.code == "hy2_salamander" }
            ?: return null

        return BootstrapTransport(
            code = hy2.code,
            priority = hy2.priority,
            networkType = hy2.networkType,
            host = entry.host,
            port = entry.port,
            auth = hy2.connectMaterial.auth,
            hy2ObfsType = hy2.connectMaterial.obfsType ?: "salamander",
            hy2ObfsPassword = hy2.connectMaterial.obfsPassword ?: hy2.connectMaterial.auth
        )
    }

    fun findRealityFallbackTransport(
        bundle: BootstrapBundle,
        realityHost: String,
        realityPort: Int,
        realityPublicKey: String,
        realityShortId: String,
        realityServerName: String
    ): BootstrapTransport? {
        val entry = bundle.entries.firstOrNull() ?: return null

        val reality = entry.transports
            .filter { isTransportUsable(it) }
            .firstOrNull { it.code == "tcp_reality_fallback" || it.code == "xhttp_reality" }
            ?: return null

        return BootstrapTransport(
            code = reality.code,
            priority = reality.priority,
            networkType = reality.networkType,
            host = entry.host,
            port = entry.port,
            realityHost = realityHost,
            realityPort = realityPort,
            realityUuid = reality.connectMaterial.clientId,
            realityPublicKey = realityPublicKey,
            realityShortId = realityShortId,
            realityServerName = realityServerName
        )
    }
}