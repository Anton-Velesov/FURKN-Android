package com.furkn.vpn

import io.nekohasekai.libbox.Notification as LibboxNotification
import com.furkn.vpn.domain.selector.TransportSelectorRegistry
import java.net.HttpURLConnection
import java.net.URL
import android.os.Handler
import android.os.Looper
import io.nekohasekai.libbox.*
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.content.pm.PackageManager
import com.furkn.vpn.SplitTunnelPrefs

class MyVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var commandServer: CommandServer? = null
    private var libboxPlatform: LibboxPlatform? = null
    private var activeProfileId: String? = null
    private var lastStartIntent: Intent? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var healthCheckPosted = false
    private var fallbackAttempted = false
    private var lastAuth: String? = null
    private val forceFallbackForTest = false
    private val profiles = listOf(
        VpnProfile(
            id = "hy2_primary",
            type = "hy2",
            host = "illfurkn.com",
            port = 443,
            auth = "",
            priority = 1
        ),
        VpnProfile(
            id = "reality_tcp_fallback",
            type = "reality",
            host = "illfurkn.com",
            port = 443,
            auth = "",
            priority = 2
        )
    )

    private fun getPrimaryProfile(auth: String): VpnProfile {
        val base = profiles.first { it.id == "hy2_primary" }

        val host = intentSafeString(EXTRA_HOST) ?: base.host
        val port = intentSafeInt(EXTRA_PORT) ?: base.port

        return base.copy(
            host = host,
            port = port,
            auth = auth
        )
    }

    private fun getOrderedProfiles(auth: String): List<VpnProfile> {
        Log.d("VPN_SELECTOR", "getOrderedProfiles() CALLED authPresent=${auth.isNotBlank()}")

        val bootstrapProfiles = buildProfilesFromBootstrap()

        val baseProfiles = if (!bootstrapProfiles.isNullOrEmpty()) {
            bootstrapProfiles
        } else {
            profiles.sortedBy { it.priority }
        }

        val host = intentSafeString(EXTRA_HOST)
        val port = intentSafeInt(EXTRA_PORT)

        val preparedProfiles = baseProfiles.map {
            if (it.type == "hy2") {
                it.copy(
                    host = host ?: it.host,
                    port = port ?: it.port,
                    auth = auth
                )
            } else {
                it.copy(
                    auth = auth
                )
            }
        }

        preparedProfiles.forEach {
            val transportCode = mapProfileIdToTransportCode(it.id)
            val score = TransportSelectorRegistry.scoreStore.getScore(transportCode)

            Log.d(
                "VPN_SELECTOR",
                "PROFILE_BEFORE_SORT id=${it.id} transport=$transportCode score=$score priority=${it.priority}"
            )
        }

        val sortedProfiles = preparedProfiles.sortedWith(
            compareByDescending<VpnProfile> { scoreForProfile(it) }
                .thenBy { it.priority }
        )

        sortedProfiles.forEach {
            val transportCode = mapProfileIdToTransportCode(it.id)
            val score = TransportSelectorRegistry.scoreStore.getScore(transportCode)

            Log.d(
                "VPN_SELECTOR",
                "PROFILE_AFTER_SORT id=${it.id} transport=$transportCode score=$score priority=${it.priority}"
            )
        }

        return sortedProfiles
    }
    private fun intentSafeString(key: String): String? {
        return lastStartIntent?.getStringExtra(key)?.takeIf { it.isNotBlank() }
    }

    private fun intentSafeInt(key: String): Int? {
        val value = lastStartIntent?.getIntExtra(key, 0) ?: 0
        return value.takeIf { it > 0 }
    }

    private fun buildProfilesFromBootstrap(): List<VpnProfile>? {
        val rawJson = intentSafeString(EXTRA_BOOTSTRAP_JSON) ?: return null
        if (rawJson.isBlank()) return null

        return try {
            val root = JSONObject(rawJson)
            val entries = root.getJSONArray("entries")
            if (entries.length() == 0) return null

            val entry = entries.getJSONObject(0)
            val host = entry.getString("host")
            val port = entry.getInt("port")
            val transports = entry.getJSONArray("transports")

            val fallbackOrder = root
                .getJSONObject("policy")
                .getJSONObject("selector_policy")
                .getJSONArray("fallback_order")

            val result = mutableListOf<VpnProfile>()

            for (i in 0 until fallbackOrder.length()) {
                val code = fallbackOrder.getString(i)

                var matchedTransport: JSONObject? = null
                for (j in 0 until transports.length()) {
                    val candidate = transports.getJSONObject(j)
                    if (candidate.getString("code") == code) {
                        matchedTransport = candidate
                        break
                    }
                }

                if (matchedTransport == null) continue

                val connectMaterial = matchedTransport.optJSONObject("connect_material")

                when (code) {
                    "hy2_salamander" -> {
                        result += VpnProfile(
                            id = "hy2_primary",
                            type = "hy2",
                            host = host,
                            port = port,
                            auth = connectMaterial?.optString("auth").orEmpty(),
                            priority = i + 1
                        )
                    }

                    "tcp_reality_fallback" -> {
                        result += VpnProfile(
                            id = "reality_tcp_fallback",
                            type = "reality",
                            host = host,
                            port = port,
                            auth = "",
                            priority = i + 1
                        )
                    }

                    "xhttp_reality" -> {
                        result += VpnProfile(
                            id = "reality_xhttp",
                            type = "reality",
                            host = host,
                            port = port,
                            auth = "",
                            priority = i + 1
                        )
                    }
                }
            }

            Log.d(
                "FURKN_VPN",
                "BOOTSTRAP_PROFILE_QUEUE " + result.joinToString(" | ") {
                    "id=${it.id},type=${it.type},host=${it.host},port=${it.port},priority=${it.priority}"
                }
            )

            result.ifEmpty { null }
        } catch (e: Throwable) {
            Log.e("FURKN_VPN", "BOOTSTRAP_PROFILE_QUEUE_ERROR ${e.message}", e)
            null
        }
    }
    private fun buildConfigForProfile(profile: VpnProfile): String? {
        return when (profile.type) {
            "hy2" -> {
                val resolvedHost = intentSafeString(EXTRA_HOST) ?: profile.host
                val resolvedPort = intentSafeInt(EXTRA_PORT) ?: profile.port
                val hy2ObfsType = intentSafeString(EXTRA_HY2_OBFS_TYPE)
                val hy2ObfsPassword = intentSafeString(EXTRA_HY2_OBFS_PASSWORD)

                Log.d(
                    "FURKN_VPN",
                    "HY2_PARAMS host=$resolvedHost port=$resolvedPort obfsType=${hy2ObfsType ?: "null"} obfsPassword=${if (hy2ObfsPassword.isNullOrBlank()) "missing" else "set"}"
                )

                buildHy2Config(
                    host = resolvedHost,
                    port = resolvedPort,
                    password = profile.auth ?: "",
                    obfsType = hy2ObfsType,
                    obfsPassword = hy2ObfsPassword
                )
            }

            "reality" -> {
                val resolvedHost = intentSafeString(EXTRA_REALITY_HOST) ?: DEFAULT_REALITY_HOST
                val resolvedPort = intentSafeInt(EXTRA_REALITY_PORT) ?: DEFAULT_REALITY_PORT
                val resolvedUuid = intentSafeString(EXTRA_REALITY_UUID) ?: DEFAULT_REALITY_UUID
                val resolvedPublicKey = intentSafeString(EXTRA_REALITY_PUBLIC_KEY) ?: DEFAULT_REALITY_PUBLIC_KEY
                val resolvedShortId = intentSafeString(EXTRA_REALITY_SHORT_ID) ?: DEFAULT_REALITY_SHORT_ID
                val resolvedServerName = intentSafeString(EXTRA_REALITY_SERVER_NAME) ?: DEFAULT_REALITY_SERVER_NAME

                Log.d(
                    "FURKN_VPN",
                    "REALITY_PARAMS host=$resolvedHost port=$resolvedPort serverName=$resolvedServerName shortId=${if (resolvedShortId.isBlank()) "empty" else "set"}"
                )

                buildRealityConfig(
                    host = resolvedHost,
                    port = resolvedPort,
                    uuid = resolvedUuid,
                    publicKey = resolvedPublicKey,
                    shortId = resolvedShortId,
                    serverName = resolvedServerName
                )
            }

            else -> {
                Log.d("FURKN_VPN", "UNKNOWN_PROFILE_TYPE profile=${profile.id} type=${profile.type}")
                null
            }
        }
    }

    private fun scheduleHealthCheckLog() {
        if (healthCheckPosted) return
        healthCheckPosted = true

        mainHandler.postDelayed({
            val profileAtCheck = activeProfileId
            val transportCode = mapProfileIdToTransportCode(profileAtCheck)

            Thread {
                val startedAt = System.currentTimeMillis()

                try {
                    val url = URL("https://www.gstatic.com/generate_204")
                    val connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 4000
                        readTimeout = 4000
                        instanceFollowRedirects = false
                        requestMethod = "GET"
                    }

                    val code = connection.responseCode
                    connection.disconnect()

                    val measuredLatencyMs = System.currentTimeMillis() - startedAt
                    val success = code == 204

                    Log.d(
                        "FURKN_VPN",
                        "HEALTHCHECK_RESULT activeProfileId=$profileAtCheck transportCode=$transportCode success=$success code=$code latencyMs=$measuredLatencyMs"
                    )

                    if (success) {
                        rememberTransportLatency(transportCode, measuredLatencyMs)
                        rememberTransportSuccess(transportCode, measuredLatencyMs)
                        Log.d(
                            "VPN_SELECTOR",
                            "snapshot=" + TransportSelectorRegistry.scoreStore.debugSnapshot()
                        )
                    } else {
                        rememberTransportFailure(transportCode)
                        Log.d(
                            "VPN_SELECTOR",
                            "snapshot=" + TransportSelectorRegistry.scoreStore.debugSnapshot()
                        )
                    }

                    if ((forceFallbackForTest || !success) && profileAtCheck == "hy2_primary") {
                        mainHandler.post {
                            triggerRealityFallback()
                        }
                    }
                } catch (e: Throwable) {
                    rememberTransportFailure(transportCode)

                    Log.d(
                        "FURKN_VPN",
                        "HEALTHCHECK_RESULT activeProfileId=$profileAtCheck transportCode=$transportCode success=false error=${e.message}"
                    )

                    Log.d(
                        "VPN_SELECTOR",
                        "snapshot=" + TransportSelectorRegistry.scoreStore.debugSnapshot()
                    )

                    if (profileAtCheck == "hy2_primary") {
                        mainHandler.post {
                            triggerRealityFallback()
                        }
                    }
                } finally {
                    healthCheckPosted = false
                }
            }.start()
        }, 5000)
    }

    private fun triggerRealityFallback() {
        if (fallbackAttempted) return
        val auth = lastAuth ?: return
        val currentId = activeProfileId ?: return

        fallbackAttempted = true

        val ordered = getOrderedProfiles(auth)

        val candidates = ordered
            .filter { it.id != currentId }

        candidates.forEach {
            val transportCode = mapProfileIdToTransportCode(it.id)
            val score = TransportSelectorRegistry.scoreStore.getScore(transportCode)

            Log.d(
                "VPN_SELECTOR",
                "FALLBACK_CANDIDATE id=${it.id} transport=$transportCode score=$score priority=${it.priority}"
            )
        }

        val nextProfile = candidates.firstOrNull()

        if (nextProfile == null) {
            Log.d("FURKN_VPN", "FALLBACK_SKIPPED reason=no_candidate_profile")
            return
        }

        Log.d(
            "FURKN_VPN",
            "FALLBACK_TRIGGER from=$currentId to=${nextProfile.id}"
        )

        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null

        libboxPlatform?.tunFd?.close()
        libboxPlatform = null

        vpnInterface?.close()
        vpnInterface = null

        val configText = buildConfigForProfile(nextProfile)

        if (configText != null) {
            val configFile = File(filesDir, "singbox-config.json")
            configFile.writeText(configText)

            activeProfileId = nextProfile.id
            scheduleHealthCheckLog()

            try {
                val handler = object : CommandServerHandler {
                    override fun writeDebugMessage(msg: String) {
                        Log.d("FURKN_BOX", msg)
                    }

                    override fun serviceReload() {}
                    override fun serviceStop() {}
                    override fun triggerNativeCrash() {}
                    override fun setSystemProxyEnabled(enabled: Boolean) {}
                    override fun getSystemProxyStatus(): SystemProxyStatus {
                        return SystemProxyStatus()
                    }
                }

                libboxPlatform = LibboxPlatform(this)

                commandServer = CommandServer(handler, libboxPlatform!!)
                commandServer!!.start()
                commandServer!!.startOrReloadService(configText, OverrideOptions())

                Log.d(
                    "FURKN_VPN",
                    "FALLBACK_APPLIED id=${nextProfile.id} type=${nextProfile.type}"
                )
            } catch (e: Throwable) {
                Log.e("FURKN_VPN", "FALLBACK_FAILED ${e.message}", e)
            }
        } else {
            Log.d("FURKN_VPN", "FALLBACK_SKIPPED reason=config_build_failed")
        }
    }

    private fun rememberTransportSuccess(transportCode: String, latencyMs: Long?) {
        TransportSelectorRegistry.scoreStore.markSuccess(transportCode, latencyMs)
        Log.d(
            "VPN_SELECTOR",
            "SUCCESS transport=$transportCode latency=$latencyMs score=" +
                    TransportSelectorRegistry.scoreStore.getScore(transportCode)
        )
    }

    private fun rememberTransportFailure(transportCode: String) {
        TransportSelectorRegistry.scoreStore.markFailure(transportCode)
        Log.d(
            "VPN_SELECTOR",
            "FAIL transport=$transportCode score=" +
                    TransportSelectorRegistry.scoreStore.getScore(transportCode)
        )
    }

    private fun rememberTransportLatency(transportCode: String, latencyMs: Long) {
        TransportSelectorRegistry.scoreStore.updateLatency(transportCode, latencyMs)
        Log.d(
            "VPN_SELECTOR",
            "LATENCY transport=$transportCode latency=$latencyMs score=" +
                    TransportSelectorRegistry.scoreStore.getScore(transportCode)
        )
    }

    private fun mapProfileIdToTransportCode(profileId: String?): String {
        return when (profileId) {
            "hy2_primary" -> "hy2_salamander"
            "reality_tcp_fallback" -> "tcp_reality_fallback"
            "reality_xhttp" -> "xhttp_reality"
            else -> profileId ?: "unknown_transport"
        }
    }

    private fun scoreForProfile(profile: VpnProfile): Int {
        val transportCode = mapProfileIdToTransportCode(profile.id)
        return TransportSelectorRegistry.scoreStore.getScore(transportCode)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafe()
        lastStartIntent = intent
        TransportSelectorRegistry.init(this)

        when (intent?.action) {
            ACTION_CONNECT -> {
                val host = intent.getStringExtra(EXTRA_HOST)
                val port = intent.getIntExtra(EXTRA_PORT, 0)
                val transport = intent.getStringExtra(EXTRA_TRANSPORT)
                val auth = intent.getStringExtra(EXTRA_AUTH)

                Log.d(
                    "FURKN_VPN",
                    "SERVICE_CONNECT_EXTRAS host=${host ?: "null"} port=$port transport=${transport ?: "null"} auth=${if (auth.isNullOrBlank()) "missing" else "set"}"
                )

                connect(host, port, transport, auth)
            }

            ACTION_DISCONNECT -> disconnect()
        }

        return Service.START_STICKY
    }

    private fun startForegroundSafe() {
        createNotificationChannel()

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("FURKN VPN")
            .setContentText("VPN service is running")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "FURKN VPN",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "VPN connection status"
        }

        manager.createNotificationChannel(channel)
    }

    private fun connect(
        host: String?,
        port: Int,
        transport: String?,
        auth: String?
    ) {
        if (vpnInterface != null) return
        Log.d("FURKN_BOX", "connect entered")
        if (!auth.isNullOrBlank()) {
            Log.d("VPN_SELECTOR", "ABOUT_TO_CALL_getOrderedProfiles_FROM_connect")
            val ordered = getOrderedProfiles(auth).map {
                if (it.type == "hy2") {
                    it.copy(
                        host = host ?: it.host,
                        port = if (port > 0) port else it.port
                    )
                } else {
                    it
                }
            }
            ordered.forEach {
                Log.d("FURKN_VPN", "PROFILE_QUEUE id=${it.id} type=${it.type} priority=${it.priority}")
            }
        }
        LibboxTest.test()
        Log.d("FURKN_BOX", "after LibboxTest")
        try {
            val baseDir = filesDir
            baseDir.mkdirs()

            val workingDir = getExternalFilesDir(null) ?: filesDir
            workingDir.mkdirs()

            val tempDir = cacheDir
            tempDir.mkdirs()

            val options = SetupOptions().apply {
                basePath = baseDir.path
                workingPath = workingDir.path
                tempPath = tempDir.path
                debug = true
                logMaxLines = 3000
            }

            Libbox.setup(options)
            Log.d("FURKN_BOX", "after Libbox.setup")
            try {
                val handler = object : CommandServerHandler {
                    override fun writeDebugMessage(msg: String) {
                        Log.d("FURKN_BOX", msg)
                    }

                    override fun serviceReload() {}
                    override fun serviceStop() {}
                    override fun triggerNativeCrash() {}
                    override fun setSystemProxyEnabled(enabled: Boolean) {}
                    override fun getSystemProxyStatus(): SystemProxyStatus {
                        return SystemProxyStatus()
                    }
                }

                libboxPlatform = LibboxPlatform(this)


                Log.d("FURKN_BOX", "before CommandServer create")
                commandServer = CommandServer(handler, libboxPlatform!!)
                Log.d("FURKN_BOX", "after CommandServer create")
                Log.d("FURKN_BOX", "before server.start")
                commandServer!!.start()
                Log.d("FURKN_BOX", "after server.start")

                val config = File(filesDir, "singbox-config.json").readText()

                Log.d("FURKN_BOX", config)
                commandServer!!.startOrReloadService(config, OverrideOptions())

                Log.d("FURKN_BOX", "SERVER STARTED")
            } catch (e: Throwable) {
                Log.e("FURKN_BOX", "Start error: ${e.message}", e)
            }
            Log.d("FURKN_LIBBOX", "Libbox setup done")
        } catch (e: Throwable) {
            Log.e("FURKN_LIBBOX", "Libbox setup error: ${e.message}", e)
        }
        val boxLoaded = BoxLoader.load()
        Log.d("FURKN_BOX", "BoxLoader result=$boxLoaded")

        Log.d(
            "FURKN_VPN",
            "CONNECT host=$host port=$port transport=$transport auth=${if (auth.isNullOrBlank()) "missing" else "received"}"
        )


        if (!auth.isNullOrBlank() && transport == "hy2_salamander") {
            lastAuth = auth
            val realityUuid = intentSafeString(EXTRA_REALITY_UUID)
            val realityPublicKey = intentSafeString(EXTRA_REALITY_PUBLIC_KEY)
            val realityShortId = intentSafeString(EXTRA_REALITY_SHORT_ID)
            val realityServerName = intentSafeString(EXTRA_REALITY_SERVER_NAME)
            val realityHost = intentSafeString(EXTRA_REALITY_HOST)
            val realityPort = intentSafeInt(EXTRA_REALITY_PORT)
            val hy2ObfsType = intentSafeString(EXTRA_HY2_OBFS_TYPE)
            val hy2ObfsPassword = intentSafeString(EXTRA_HY2_OBFS_PASSWORD)

            Log.d(
                "FURKN_VPN",
                "CONNECT_INTENT hy2ObfsType=${hy2ObfsType ?: "null"} hy2ObfsPassword=${if (hy2ObfsPassword.isNullOrBlank()) "missing" else "set"} realityHost=${realityHost ?: "null"} realityPort=${realityPort ?: 0} realityUuid=${if (realityUuid.isNullOrBlank()) "missing" else "set"} realityPublicKey=${if (realityPublicKey.isNullOrBlank()) "missing" else "set"} realityServerName=${realityServerName ?: "null"} realityShortId=${if (realityShortId.isNullOrBlank()) "empty" else "set"}"
            )
            val ordered = getOrderedProfiles(auth).map {
                if (it.type == "hy2") {
                    it.copy(
                        host = host ?: it.host,
                        port = if (port > 0) port else it.port
                    )
                } else {
                    it
                }
            }

            ordered.forEach {
                val transportCode = mapProfileIdToTransportCode(it.id)
                val score = TransportSelectorRegistry.scoreStore.getScore(transportCode)

                Log.d(
                    "VPN_SELECTOR",
                    "PRECONNECT_CANDIDATE id=${it.id} transport=$transportCode score=$score priority=${it.priority}"
                )
            }

            var selectedProfile: VpnProfile? = null
            var configText: String? = null

            for (profile in ordered) {
                val transportCode = mapProfileIdToTransportCode(profile.id)
                val score = TransportSelectorRegistry.scoreStore.getScore(transportCode)

                Log.d(
                    "VPN_SELECTOR",
                    "PRECONNECT_TRY id=${profile.id} transport=$transportCode score=$score"
                )

                val attempt = buildConfigForProfile(profile)

                if (attempt != null) {
                    selectedProfile = profile
                    configText = attempt

                    Log.d(
                        "VPN_SELECTOR",
                        "PRECONNECT_SELECTED id=${profile.id} transport=$transportCode score=$score"
                    )
                    break
                } else {
                    Log.d("FURKN_VPN", "PROFILE_SKIPPED id=${profile.id}")
                }
            }

            if (selectedProfile != null && configText != null) {
                val configFile = File(filesDir, "singbox-config.json")
                configFile.writeText(configText)

                Log.d("FURKN_VPN", "PROFILE_SELECTED id=${selectedProfile.id} type=${selectedProfile.type}")
                activeProfileId = selectedProfile.id
                scheduleHealthCheckLog()
                Log.d("FURKN_VPN", "CONFIG_WRITTEN path=${configFile.absolutePath}")
                Log.d("FURKN_VPN", "CONFIG_PREVIEW ${configText.take(300)}")
            } else {
                Log.d("FURKN_VPN", "NO_VALID_PROFILE_FOUND")
            }

        } else {
            Log.d("FURKN_VPN", "CONFIG_SKIPPED invalid connect params")
        }
    }

    private fun buildHy2Config(
        host: String,
        port: Int,
        password: String,
        obfsType: String?,
        obfsPassword: String?
    ): String {
        val tunInbound = JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("interface_name", "tun0")
            .put("address", JSONArray().put("172.19.0.1/30"))
            .put("auto_route", true)
            .put("strict_route", true)
            .put("stack", "system")

        val hy2Outbound = JSONObject()
            .put("type", "hysteria2")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("password", password)
            .put("up_mbps", 50)
            .put("down_mbps", 100)

        if (!obfsType.isNullOrBlank() && !obfsPassword.isNullOrBlank()) {
            hy2Outbound.put(
                "obfs",
                JSONObject()
                    .put("type", obfsType)
                    .put("password", obfsPassword)
            )
        }

        hy2Outbound.put(
            "tls",
            JSONObject()
                .put("enabled", true)
                .put("server_name", host)
        )

        val directOutbound = JSONObject()
            .put("type", "direct")
            .put("tag", "direct")

        val route = JSONObject()
            .put("final", "proxy")

        val dns = JSONObject()
            .put(
                "servers",
                JSONArray()
                    .put(JSONObject().put("tag", "cloudflare").put("address", "1.1.1.1"))
                    .put(JSONObject().put("tag", "google").put("address", "8.8.8.8"))
            )

        val root = JSONObject()
            .put("log", JSONObject().put("level", "debug"))
            .put("dns", dns)
            .put("inbounds", JSONArray().put(tunInbound))
            .put("outbounds", JSONArray().put(hy2Outbound).put(directOutbound))
            .put("route", route)
            .put("final", "proxy")
            .put("auto_detect_interface", true)

        return root.toString(2)
    }

    private fun buildRealityConfig(
        host: String,
        port: Int,
        uuid: String,
        publicKey: String,
        shortId: String,
        serverName: String
    ): String {
        val tunInbound = JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("interface_name", "tun0")
            .put("address", JSONArray().put("172.19.0.1/30"))
            .put("auto_route", true)
            .put("strict_route", true)
            .put("stack", "system")

        val vlessOutbound = JSONObject()
            .put("type", "vless")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("uuid", uuid)
            .put("flow", "")
            .put(
                "tls",
                JSONObject()
                    .put("enabled", true)
                    .put("server_name", serverName)
                    .put(
                        "reality",
                        JSONObject()
                            .put("enabled", true)
                            .put("public_key", publicKey)
                            .put("short_id", shortId)
                    )
                    .put("utls", JSONObject().put("enabled", true).put("fingerprint", "chrome"))
            )
            .put("packet_encoding", "xudp")
            .put("transport", JSONObject().put("type", "tcp"))

        val directOutbound = JSONObject()
            .put("type", "direct")
            .put("tag", "direct")

        val route = JSONObject()
            .put("final", "proxy")

        val dns = JSONObject()
            .put(
                "servers",
                JSONArray()
                    .put(JSONObject().put("tag", "cloudflare").put("address", "1.1.1.1"))
                    .put(JSONObject().put("tag", "google").put("address", "8.8.8.8"))
            )

        val root = JSONObject()
            .put("log", JSONObject().put("level", "debug"))
            .put("dns", dns)
            .put("inbounds", JSONArray().put(tunInbound))
            .put("outbounds", JSONArray().put(vlessOutbound).put(directOutbound))
            .put("route", route)
            .put("final", "proxy")
            .put("auto_detect_interface", true)

        return root.toString(2)
    }

    private fun disconnect() {
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null

        libboxPlatform?.tunFd?.close()
        libboxPlatform = null

        vpnInterface?.close()
        vpnInterface = null

        activeProfileId = null
        healthCheckPosted = false
        fallbackAttempted = false
        lastAuth = null
        mainHandler.removeCallbacksAndMessages(null)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CONNECT = "com.furkn.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.furkn.vpn.DISCONNECT"

        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_TRANSPORT = "transport"
        const val EXTRA_AUTH = "auth"
        const val EXTRA_BOOTSTRAP_JSON = "bootstrap_json"
        const val EXTRA_HY2_OBFS_TYPE = "hy2_obfs_type"
        const val EXTRA_HY2_OBFS_PASSWORD = "hy2_obfs_password"
        const val EXTRA_REALITY_UUID = "reality_uuid"
        const val EXTRA_REALITY_PUBLIC_KEY = "reality_public_key"
        const val EXTRA_REALITY_SHORT_ID = "reality_short_id"
        const val EXTRA_REALITY_SERVER_NAME = "reality_server_name"
        const val EXTRA_REALITY_HOST = "reality_host"
        const val EXTRA_REALITY_PORT = "reality_port"

        private const val CHANNEL_ID = "furkn_vpn_channel"
        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_REALITY_HOST = "178.156.169.112"
        private const val DEFAULT_REALITY_PORT = 443
        private const val DEFAULT_REALITY_UUID = "725115ea-c379-4fb3-be23-bd357cd11797"
        private const val DEFAULT_REALITY_PUBLIC_KEY = "RPbYrqwFQ96tCCJFnI3mYyDM4c8tYggduJKflhw5eiY"
        private const val DEFAULT_REALITY_SHORT_ID = ""
        private const val DEFAULT_REALITY_SERVER_NAME = "www.google.com"
    }
}