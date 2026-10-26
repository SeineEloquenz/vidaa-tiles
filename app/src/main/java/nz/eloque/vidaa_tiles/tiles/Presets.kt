package nz.eloque.vidaa_tiles.tiles

import java.net.URI
import java.net.URLEncoder

data class TileSpec(
    val appId: String,
    val name: String,
    val url: String,
    val image: String,
)

enum class ServerInput { NONE, REQUIRED, OPTIONAL }

/** One-tap tiles, ported from Sidee's `core/presets.py`. App ids match Sidee so reinstalling replaces its tiles. */
enum class Preset(
    val title: String,
    val description: String,
    val server: ServerInput,
) {
    JELLYFIN("Jellyfin", "Your media from your Jellyfin server", ServerInput.REQUIRED),
    STREMIO("Stremio", "Stremio TV build with D-pad navigation", ServerInput.OPTIONAL),
    NUVIO("Nuvio", "Browse and stream your library", ServerInput.NONE),
    ;

    fun build(server: String): TileSpec =
        when (this) {
            JELLYFIN ->
                TileSpec(
                    "jellyfin",
                    title,
                    Presets.jellyfinUrl(server),
                    "https://raw.githubusercontent.com/mamsxiii/Sidee/main/assets/jellyfin-tile-bold.png",
                )
            STREMIO ->
                TileSpec(
                    "stremiodebug",
                    title,
                    Presets.stremioUrl(server),
                    "https://noobygains.github.io/stremio-vidaa-tv/icon.png",
                )
            NUVIO ->
                TileSpec(
                    "nuviodebug",
                    title,
                    "https://nuviotvsmart.vercel.app/vidaa.html",
                    "https://nuviotvsmart.vercel.app/assets/images/tizenIcon.png",
                )
        }
}

object Presets {
    private const val STREMIO_BASE = "https://noobygains.github.io/stremio-vidaa-tv/?install_source=sidee"

    fun jellyfinUrl(server: String): String {
        val trimmed = server.trim().trimEnd('/')
        val hasScheme = "://" in trimmed
        val uri = parse(if (hasScheme) trimmed else "http://$trimmed") ?: invalid("Enter your Jellyfin server's LAN IP or full URL.")
        val host = uri.host?.trimEnd('.') ?: invalid("Enter your Jellyfin server's LAN IP or full URL.")
        requireNotLoopback(host, "Use your Jellyfin server's LAN IP or URL. localhost refers to the TV itself.")
        val scheme =
            if (hasScheme) {
                uri.scheme
            } else if (isPrivateHost(host)) {
                "http"
            } else {
                "https"
            }
        val authority = if (!hasScheme && uri.port == -1) "${uri.rawAuthority}:8096" else uri.rawAuthority
        var path = uri.rawPath.orEmpty().trimEnd('/')
        if (!Regex("/web(/index\\.html)?$").containsMatchIn(path)) path += "/web/index.html"
        return buildString {
            append(scheme).append("://").append(authority).append(path)
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
    }

    fun stremioUrl(server: String): String {
        val trimmed = server.trim().trimEnd('/')
        if (trimmed.isEmpty()) return STREMIO_BASE
        val uri = parse(trimmed)
        if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrEmpty()) {
            invalid("Paste the full Stremio streaming server URL, including http:// or https://.")
        }
        requireNotLoopback(uri.host.trimEnd('.'), "Use a Stremio server address the TV can reach. localhost points to the TV itself.")
        return STREMIO_BASE + "&server=" + URLEncoder.encode(trimmed, Charsets.UTF_8.name()).replace("+", "%20")
    }

    fun custom(
        name: String,
        url: String,
        image: String,
    ): TileSpec {
        val cleanName = name.trim().split(Regex("\\s+")).joinToString(" ")
        val cleanImage = image.trim()
        var cleanUrl = url.trim()
        if (cleanName.isEmpty()) invalid("Enter a name for your shortcut.")
        if (!Regex("[\\w \\-']{1,40}").matches(cleanName)) {
            invalid("Keep the name under 40 characters, using letters, digits, spaces, hyphens or apostrophes.")
        }
        if (cleanUrl.isEmpty()) invalid("Enter the web address the tile should open.")
        if ("://" !in cleanUrl) cleanUrl = (if (isPrivateHost(hostOf(cleanUrl))) "http://" else "https://") + cleanUrl
        val host = parse(cleanUrl)?.host?.trimEnd('.') ?: invalid("Enter a valid web address.")
        requireNotLoopback(host, "localhost refers to the TV itself. Use an address your TV can reach.")
        if (cleanImage.isNotEmpty() && !cleanImage.startsWith("https://")) {
            invalid("The image must be an https:// address, because the TV downloads it itself.")
        }
        return TileSpec(customAppId(cleanName), cleanName, cleanUrl, cleanImage)
    }

    fun customAppId(name: String): String {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return if (slug.isEmpty()) "my_tile" else ("my_$slug").take(32).trimEnd('_')
    }

    fun isPrivateHost(host: String): Boolean {
        if (host.endsWith(".local")) return true
        val o = ipv4(host) ?: return host.startsWith("[fc") || host.startsWith("[fd")
        return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)
    }

    private fun requireNotLoopback(
        host: String,
        message: String,
    ) {
        val lower = host.lowercase()
        val octets = ipv4(lower)
        val loopback =
            lower == "localhost" ||
                lower.endsWith(".localhost") ||
                lower == "[::1]" ||
                lower == "[::]" ||
                (octets != null && (octets[0] == 127 || octets.all { it == 0 }))
        if (loopback) invalid(message)
    }

    private fun ipv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        return parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }.toIntArray()
    }

    private fun hostOf(url: String) = parse("http://$url")?.host.orEmpty()

    private fun parse(url: String): URI? = runCatching { URI(url) }.getOrNull()

    private fun invalid(message: String): Nothing = throw IllegalArgumentException(message)
}
