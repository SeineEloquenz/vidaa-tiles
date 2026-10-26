package nz.eloque.vidaa_tiles.tv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

data class Credentials(
    val clientId: String,
    val username: String,
    val password: String,
)

data class Topics(
    val clientId: String,
) {
    val ui = "/remoteapp/tv/ui_service/$clientId/"
    val platform = "/remoteapp/tv/platform_service/$clientId/"
    val mobile = "/remoteapp/mobile/$clientId/"
    val applistReply = mobile + "ui_service/data/applist"
    val tokenReply = mobile + "platform_service/data/tokenissuance"
}

data class TokenGrant(
    val accessToken: String,
    val refreshToken: String?,
    val accessDays: Long?,
    val refreshDays: Long?,
)

data class Tile(
    val appId: String,
    val name: String,
    val url: String,
)

/** Wire format of the VIDAA remote-app MQTT protocol, ported from Sidee's `core/protocol.py`. */
object Protocol {
    private const val BRAND = "his"
    private const val OPERATION = "vidaacommon_001"
    private val KEY = "v1tile".toByteArray()

    private fun decode(encoded: String) = Base85.decode(encoded).xor(KEY)

    internal val pattern = String(decode("MF}upSs^t7L@!uSM*&SJUsF\$TOkXM{GYLXpEnP!%"), Charsets.US_ASCII)
    internal val valueSuffix = String(decode("9uOTVA4XC~Q2;UlP*7e7"), Charsets.US_ASCII)
    internal val xorMask = BigInteger(1, decode("AgN#;M`qty"))

    private val json = Json { ignoreUnknownKeys = true }

    private fun md5Upper(s: String): String =
        MessageDigest
            .getInstance("MD5")
            .digest(s.toByteArray())
            .joinToString("") { "%02X".format(it) }

    fun newDeviceUuid(): String {
        val bytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString(":") { "%02x".format(it) }
    }

    /** Without a token the password is derived from the TV clock, with one the token is the password. */
    fun credentials(
        deviceUuid: String,
        tvTimestamp: Long,
        token: String? = null,
    ): Credentials {
        val clean = deviceUuid.replace("-", ":").lowercase()
        val race = md5Upper("$pattern$$clean").take(6)
        val clientId = "$clean$$BRAND$${race}_$OPERATION"
        val username = "$BRAND$${BigInteger.valueOf(tvTimestamp).xor(xorMask)}"
        if (token != null) return Credentials(clientId, username, token)
        val digit = tvTimestamp.toString().sumOf { it.digitToInt() } % 10
        val valueMd5 = md5Upper("$BRAND$digit$valueSuffix").take(6)
        return Credentials(clientId, username, md5Upper("$tvTimestamp$$valueMd5"))
    }

    fun pairConnectPayload(): String =
        buildJsonObject {
            put("app_version", 2)
            put("connect_result", 0)
            put("device_type", "Mobile App")
        }.toString()

    fun pairPinPayload(pin: Int): String = buildJsonObject { put("authNum", pin) }.toString()

    fun tokenRequestPayload(refreshToken: String): String = buildJsonObject { put("refreshtoken", refreshToken) }.toString()

    fun installPayload(
        appId: String,
        name: String,
        url: String,
        image: String,
    ): String =
        buildJsonObject {
            put("type", "app_install")
            putJsonObject("app_info") {
                put("Title", name)
                put("StoreType", 99)
                put("mediaId", appId)
                put("Id", appId)
                put("Image", image)
                put("URL", url)
                put("configUrlDownload", 0)
                put("configUrl", "")
            }
        }.toString()

    fun launchPayload(
        appId: String,
        name: String,
        url: String,
    ): String =
        buildJsonObject {
            put("appId", appId)
            put("name", name)
            put("url", url)
            put("urlType", 37)
            put("appName", name)
            put("appUrl", url)
        }.toString()

    fun parseToken(payload: String): TokenGrant? {
        val obj = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null

        fun field(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
        val access = field("accesstoken")?.takeIf { it.isNotEmpty() } ?: return null
        return TokenGrant(
            accessToken = access,
            refreshToken = field("refreshtoken")?.takeIf { it.isNotEmpty() },
            accessDays = field("accesstoken_duration_day")?.toLongOrNull(),
            refreshDays = field("refreshtoken_duration_day")?.toLongOrNull(),
        )
    }

    fun parsePinResult(payload: String): Boolean? {
        val obj = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
        val result = (obj["result"] as? JsonPrimitive)?.contentOrNull ?: return null
        return result == "1"
    }

    fun parseAppList(payload: String): List<Tile>? {
        val array = runCatching { json.parseToJsonElement(payload) as? JsonArray }.getOrNull() ?: return null
        return array.filterIsInstance<JsonObject>().map { app ->
            fun field(key: String) = runCatching { app[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
            Tile(
                appId = field("appId").orEmpty(),
                name = field("name").orEmpty(),
                url = listOf("url", "appUrl", "URL").firstNotNullOfOrNull { field(it)?.trim()?.takeIf(String::isNotEmpty) }.orEmpty(),
            )
        }
    }
}
