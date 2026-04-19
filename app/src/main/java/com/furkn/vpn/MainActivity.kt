package com.furkn.vpn

import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.furkn.vpn.ui.AppScreen
import com.furkn.vpn.ui.theme.FURKNVPNTheme
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    private var pendingConnect = false
    private var pendingLoginToken: String? = null
    private var backendBootstrapJson: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()

        setContent {
            FURKNVPNTheme {
                AppScreen(
                    onConnectRequest = {
                        // твой текущий запуск VPN permission / bootstrap
                    },
                    onDisconnectRequest = {
                        stopVpn()
                    }
                )
            }
        }
    }

    private fun buildTestBootstrapBundle(): BootstrapBundle {
        val json = backendBootstrapJson ?: assets.open("bootstrap.json").use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }

        return BootstrapParser.parse(json)
    }

    private fun startVpn() {
        val bundle = buildTestBootstrapBundle()

        Log.d(
            "FURKN_VPN",
            "BUNDLE_DEBUG entryHost=" + (bundle.entries.firstOrNull()?.host ?: "null") +
                    " entryPort=" + (bundle.entries.firstOrNull()?.port?.toString() ?: "null")
        )

        Log.d(
            "FURKN_VPN",
            "TRANSPORTS_DEBUG " + bundle.entries.firstOrNull()?.transports?.joinToString(" | ") {
                "code=" + it.code +
                        ", status=" + it.provisioningStatus +
                        ", auth=" + (it.connectMaterial.auth ?: "null") +
                        ", clientId=" + (it.connectMaterial.clientId ?: "null")
            }
        )

        val entry = bundle.entries.firstOrNull() ?: run {
            Log.e("FURKN_VPN", "BOOTSTRAP_ENTRY_MISSING")
            return
        }

        val hy2Raw = entry.transports.firstOrNull { it.code == "hy2_salamander" } ?: run {
            Log.e("FURKN_VPN", "BOOTSTRAP_HY2_RAW_MISSING")
            return
        }

        val primaryTransport = BootstrapTransport(
            code = hy2Raw.code,
            priority = hy2Raw.priority,
            networkType = hy2Raw.networkType,
            host = entry.host,
            port = entry.port,
            auth = hy2Raw.connectMaterial.auth,
            hy2ObfsType = hy2Raw.connectMaterial.obfsType?.ifBlank { "salamander" } ?: "salamander",
            hy2ObfsPassword = hy2Raw.connectMaterial.obfsPassword?.ifBlank {
                hy2Raw.connectMaterial.auth ?: ""
            } ?: (hy2Raw.connectMaterial.auth ?: "")
        )

        val fallbackTransport = BootstrapMapper.findRealityFallbackTransport(
            bundle = bundle,
            realityHost = "178.156.169.112",
            realityPort = 443,
            realityPublicKey = "RPbYrqwFQ96tCCJFnI3mYyDM4c8tYggduJKflhw5eiY",
            realityShortId = "",
            realityServerName = "www.google.com"
        ) ?: run {
            Log.e("FURKN_VPN", "BOOTSTRAP_FALLBACK_MISSING")
            return
        }

        val intent = Intent(this, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_CONNECT

            putExtra(MyVpnService.EXTRA_HOST, primaryTransport.host)
            putExtra(MyVpnService.EXTRA_PORT, primaryTransport.port)
            putExtra(MyVpnService.EXTRA_TRANSPORT, primaryTransport.code)
            putExtra(MyVpnService.EXTRA_AUTH, primaryTransport.auth)
            putExtra(MyVpnService.EXTRA_BOOTSTRAP_JSON, backendBootstrapJson ?: "")
            putExtra(MyVpnService.EXTRA_HY2_OBFS_TYPE, primaryTransport.hy2ObfsType)
            putExtra(MyVpnService.EXTRA_HY2_OBFS_PASSWORD, primaryTransport.hy2ObfsPassword)

            putExtra(MyVpnService.EXTRA_REALITY_HOST, fallbackTransport.realityHost)
            putExtra(MyVpnService.EXTRA_REALITY_PORT, fallbackTransport.realityPort ?: 0)
            putExtra(MyVpnService.EXTRA_REALITY_UUID, fallbackTransport.realityUuid)
            putExtra(MyVpnService.EXTRA_REALITY_PUBLIC_KEY, fallbackTransport.realityPublicKey)
            putExtra(MyVpnService.EXTRA_REALITY_SHORT_ID, fallbackTransport.realityShortId ?: "")
            putExtra(MyVpnService.EXTRA_REALITY_SERVER_NAME, fallbackTransport.realityServerName)
        }

        Log.d(
            "FURKN_VPN",
            "BOOTSTRAP_READY primary=${primaryTransport.code} fallback=${fallbackTransport.code}"
        )

        startForegroundService(intent)
    }

    private fun loadBootstrapFromBackendAndStart(token: String) {
        Thread {
            try {
                val url = URL("https://vpn.illfurkn.com/bootstrap/$token")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val response = stream?.bufferedReader()?.use { it.readText() } ?: ""

                Log.d("BOOTSTRAP_HTTP", "code=$responseCode")
                Log.d("BOOTSTRAP_HTTP", "body=$response")

                connection.disconnect()

                if (responseCode in 200..299) {
                    backendBootstrapJson = response
                    runOnUiThread {
                        startVpn()
                    }
                } else {
                    Log.e("BOOTSTRAP_HTTP", "bootstrap failed code=$responseCode")
                }
            } catch (e: Exception) {
                Log.e("BOOTSTRAP_HTTP", "error=${e.message}", e)
            }
        }.start()
    }

    private fun stopVpn() {
        val intent = Intent(this, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_DISCONNECT
        }
        startService(intent)
    }

    private fun requestVpnPermissionAndStart(loginToken: String) {
        pendingLoginToken = loginToken
        pendingConnect = true

        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            onActivityResult(VPN_REQUEST_CODE, Activity.RESULT_OK, null)
        }
    }

    private fun resumePendingVpnStart() {
        val token = pendingLoginToken
        if (token.isNullOrBlank()) {
            Log.e("FURKN_AUTH", "LOGIN_TOKEN_MISSING")
            return
        }

        Log.d("FURKN_AUTH", "SMS_LOGIN_TOKEN token=${token.take(8)}...")
        loadBootstrapFromBackendAndStart(token)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == VPN_REQUEST_CODE && pendingConnect) {
            pendingConnect = false
            if (resultCode == Activity.RESULT_OK) {
                resumePendingVpnStart()
            }
        }
    }

    companion object {
        private const val VPN_REQUEST_CODE = 1001
    }
}