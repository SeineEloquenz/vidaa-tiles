package nz.eloque.vidaa_tiles.tv

import android.content.Context
import android.net.wifi.WifiManager
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL

data class TvInfo(
    val host: String,
    val name: String,
    val model: String,
)

/** Finds VIDAA TVs with SSDP and reads the TV clock, which the pairing credentials depend on. */
class Discovery(
    private val context: Context,
) {
    fun discover(timeoutMs: Long = 4000): List<TvInfo> {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("vidaa-tiles")?.apply { setReferenceCounted(false) }
        lock?.acquire()
        val locations = linkedMapOf<String, String>()
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 500
                val targets = (directedBroadcasts() + listOf(BROADCAST, MULTICAST)).distinct()
                val search = mSearch()

                fun sendAll() =
                    targets.forEach { target ->
                        runCatching { socket.send(DatagramPacket(search, search.size, InetAddress.getByName(target), SSDP_PORT)) }
                    }
                sendAll()
                val end = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        sendAll()
                        continue
                    }
                    val text = String(packet.data, 0, packet.length)
                    if (!text.uppercase().startsWith("HTTP/1.1 200")) continue
                    LOCATION
                        .find(text)
                        ?.groupValues
                        ?.get(1)
                        ?.let { locations[packet.address.hostAddress!!] = it }
                }
            }
        } finally {
            lock?.release()
        }
        return locations.mapNotNull { (host, location) -> describe(host, location) }.sortedBy { it.host }
    }

    fun describe(
        host: String,
        location: String? = null,
    ): TvInfo? {
        val candidates = location?.let { listOf(it) } ?: UPNP_PORTS.map { "http://$host:$it$DESCRIPTOR" }
        for (candidate in candidates) {
            val xml =
                try {
                    get(candidate) { it.inputStream.bufferedReader().readText() }
                } catch (_: IOException) {
                    null
                } ?: continue
            val description =
                Regex("<modelDescription>(.*?)</modelDescription>", RegexOption.DOT_MATCHES_ALL)
                    .find(xml)
                    ?.groupValues
                    ?.get(1)
                    .orEmpty()
            val isVidaa = "vidaa_support" in description || "transport_protocol" in description || "hisense" in xml.lowercase()
            if (!isVidaa) return null
            val name = Regex("<friendlyName>([^<]+)<").find(xml)?.groupValues?.get(1) ?: host
            return TvInfo(host, name, description.trim().take(120))
        }
        return null
    }

    fun tvTimestamp(host: String): Long {
        for (port in UPNP_PORTS) {
            val date =
                try {
                    get("http://$host:$port$DESCRIPTOR") { it.getHeaderFieldDate("Date", 0) }
                } catch (_: IOException) {
                    null
                }
            if (date != null && date > 0) return date / 1000
        }
        return System.currentTimeMillis() / 1000
    }

    private fun <T> get(
        url: String,
        read: (HttpURLConnection) -> T,
    ): T? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        return try {
            if (connection.responseCode == 200) read(connection) else null
        } finally {
            connection.disconnect()
        }
    }

    private fun directedBroadcasts(): List<String> =
        runCatching {
            NetworkInterface
                .getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .filter { it.address is Inet4Address && it.networkPrefixLength < 31 }
                .mapNotNull { it.broadcast?.hostAddress }
        }.getOrDefault(emptyList())

    private fun mSearch(): ByteArray =
        listOf(
            "M-SEARCH * HTTP/1.1",
            "HOST: $MULTICAST:$SSDP_PORT",
            "MAN: \"ssdp:discover\"",
            "MX: 2",
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1",
            "CONTENT-LENGTH: 0",
            "",
            "",
        ).joinToString("\r\n").toByteArray()

    private companion object {
        const val MULTICAST = "239.255.255.250"
        const val BROADCAST = "255.255.255.255"
        const val SSDP_PORT = 1900
        const val DESCRIPTOR = "/MediaServer/rendererdevicedesc.xml"
        val UPNP_PORTS = listOf(18400, 38400, 80)
        val LOCATION = Regex("Location:\\s*(\\S+)", RegexOption.IGNORE_CASE)
    }
}
