package com.furkn.vpn

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import android.util.Log
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.security.KeyStore
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import io.nekohasekai.libbox.Notification as LibboxNotification
import io.nekohasekai.libbox.NeighborUpdateListener
import com.furkn.vpn.SplitTunnelPrefs

class LibboxPlatform(
    private val service: MyVpnService
) : PlatformInterface {

    var tunFd: ParcelFileDescriptor? = null

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        val ok = service.protect(fd)
        Log.d("FURKN_BOX", "autoDetectInterfaceControl fd=$fd protect=$ok")
    }

    override fun openTun(options: TunOptions): Int {
        Log.d("FURKN_BOX", "openTun called")

        val builder = service.Builder()
            .setSession("FURKN VPN")
            .setBlocking(false)

        val disallowedPackages = SplitTunnelPrefs.loadExcludedPackages(service)

        Log.d("FURKN_BOX", "split tunnel loaded packages count=${disallowedPackages.size}")

        disallowedPackages.forEach { packageName: String ->
            try {
                builder.addDisallowedApplication(packageName)
                Log.d("FURKN_BOX", "split tunnel exclude: $packageName")
            } catch (e: PackageManager.NameNotFoundException) {
                Log.d("FURKN_BOX", "package not installed, skip: $packageName")
            } catch (e: Exception) {
                Log.e("FURKN_BOX", "failed to exclude package: $packageName", e)
            }
        }

        val mtu = options.getMTU()
        if (mtu > 0) builder.setMtu(mtu)

        val inet4 = options.getInet4Address()
        while (inet4.hasNext()) {
            val prefix = inet4.next().toString()
            val parts = prefix.split("/")
            if (parts.size == 2) {
                builder.addAddress(parts[0], parts[1].toInt())
            }
        }

        val dns = runCatching { options.getDNSServerAddress() }.getOrNull()
        if (dns != null) {
            val dnsText = dns.toString()
            if (dnsText.isNotBlank()) {
                dnsText
                    .split("\n", ",", " ")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { runCatching { builder.addDnsServer(it) } }
            }
        } else {
            builder.addDnsServer("1.1.1.1")
            builder.addDnsServer("8.8.8.8")
        }

        if (options.getAutoRoute()) {
            builder.addRoute("0.0.0.0", 0)
        }

        tunFd?.close()
        tunFd = builder.establish()

        val fd = tunFd?.fd ?: -1
        Log.d("FURKN_BOX", "openTun fd=$fd")
        return fd
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun includeAllNetworks(): Boolean = false

    override fun clearDNSCache() {
    }

    override fun underNetworkExtension(): Boolean = false

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
    }

    override fun startNeighborMonitor(listener: NeighborUpdateListener) {
    }

    override fun closeNeighborMonitor(listener: NeighborUpdateListener) {
    }

    override fun registerMyInterface(name: String) {
        Log.d("FURKN_BOX", "registerMyInterface $name")
    }

    override fun sendNotification(notification: LibboxNotification) {
        Log.d("FURKN_BOX", "libbox notification: ${notification.title} / ${notification.body}")
    }

    override fun readWIFIState(): WIFIState? {
        return try {
            val wifiManager = service.applicationContext.getSystemService(WifiManager::class.java)
            val wifiInfo = wifiManager?.connectionInfo ?: return null
            var ssid = wifiInfo.ssid ?: return WIFIState("", "")
            if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length - 1)
            }
            WIFIState(ssid, wifiInfo.bssid ?: "")
        } catch (_: Throwable) {
            null
        }
    }

    override fun localDNSTransport(): LocalDNSTransport? = null

    @OptIn(ExperimentalEncodingApi::class)
    override fun systemCertificates(): StringIterator {
        val certificates = mutableListOf<String>()
        val keyStore = KeyStore.getInstance("AndroidCAStore")
        keyStore.load(null, null)
        val aliases = keyStore.aliases()
        while (aliases.hasMoreElements()) {
            val cert = keyStore.getCertificate(aliases.nextElement())
            certificates.add(
                "-----BEGIN CERTIFICATE-----\n" +
                        Base64.encode(cert.encoded) +
                        "\n-----END CERTIFICATE-----"
            )
        }
        return StringArray(certificates.iterator())
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val connectivity = service.getSystemService(ConnectivityManager::class.java)
        val networks = connectivity?.allNetworks ?: emptyArray()
        val networkInterfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
        val interfaces = mutableListOf<LibboxNetworkInterface>()

        for (network in networks) {
            val linkProperties = connectivity?.getLinkProperties(network) ?: continue
            val networkCapabilities = connectivity.getNetworkCapabilities(network) ?: continue

            val boxInterface = LibboxNetworkInterface()
            boxInterface.name = linkProperties.interfaceName

            val networkInterface = networkInterfaces.find { it.name == boxInterface.name } ?: continue

            boxInterface.dnsServer = StringArray(
                linkProperties.dnsServers.mapNotNull { it.hostAddress }.iterator()
            )

            boxInterface.type = when {
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                else -> Libbox.InterfaceTypeOther
            }

            boxInterface.index = networkInterface.index
            runCatching { boxInterface.mtu = networkInterface.mtu }

            boxInterface.addresses = StringArray(
                networkInterface.interfaceAddresses.map { it.toPrefix() }.iterator()
            )

            var dumpFlags = 0
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                dumpFlags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            }
            if (networkInterface.isLoopback) dumpFlags = dumpFlags or OsConstants.IFF_LOOPBACK
            if (networkInterface.isPointToPoint) dumpFlags = dumpFlags or OsConstants.IFF_POINTOPOINT
            if (networkInterface.supportsMulticast()) dumpFlags = dumpFlags or OsConstants.IFF_MULTICAST

            boxInterface.flags = dumpFlags
            boxInterface.metered =
                !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

            interfaces.add(boxInterface)
        }

        Log.d("FURKN_BOX", "getInterfaces count=${interfaces.size}")
        return InterfaceArray(interfaces.iterator())
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                ConnectionOwner().apply {
                    userId = Process.INVALID_UID
                    userName = ""
                    processPath = ""
                    setAndroidPackageNames(StringArray(emptyList<String>().iterator()))
                }
            } else {
                val connectivity = service.getSystemService(ConnectivityManager::class.java)
                val uid = connectivity?.getConnectionOwnerUid(
                    ipProtocol,
                    InetSocketAddress(sourceAddress, sourcePort),
                    InetSocketAddress(destinationAddress, destinationPort)
                ) ?: Process.INVALID_UID

                val packages = service.packageManager.getPackagesForUid(uid)?.toList() ?: emptyList()

                ConnectionOwner().apply {
                    userId = uid
                    userName = packages.firstOrNull() ?: ""
                    processPath = ""
                    setAndroidPackageNames(StringArray(packages.iterator()))
                }
            }
        } catch (e: Throwable) {
            Log.e("FURKN_BOX", "findConnectionOwner error: ${e.message}", e)
            ConnectionOwner().apply {
                userId = Process.INVALID_UID
                userName = ""
                processPath = ""
                setAndroidPackageNames(StringArray(emptyList<String>().iterator()))
            }
        }
    }

    private class InterfaceArray(
        private val iterator: Iterator<LibboxNetworkInterface>
    ) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): LibboxNetworkInterface = iterator.next()
    }

    class StringArray(
        private val iterator: Iterator<String>
    ) : StringIterator {
        override fun len(): Int = 0
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
    }

    private fun InterfaceAddress.toPrefix(): String {
        return if (address is Inet6Address) {
            "${Inet6Address.getByAddress(address.address).hostAddress}/$networkPrefixLength"
        } else {
            "${address.hostAddress}/$networkPrefixLength"
        }
    }
}