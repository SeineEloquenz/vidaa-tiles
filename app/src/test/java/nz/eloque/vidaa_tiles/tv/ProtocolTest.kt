package nz.eloque.vidaa_tiles.tv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class ProtocolTest {
    private val ts = 1791229505L
    private val uuid = "16:1e:d5:d4:61:0d"

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun base85MatchesPython() {
        assertEquals("hello", String(Base85.decode("Xk~0{Zv")))
    }

    @Test
    fun decodedConstantsMatchSideeDigests() {
        assertEquals("96f682e0558c63d36eebd6971b8628729de55d8a33615482bd4c95ffa38daf76", sha256(Protocol.pattern))
        assertEquals("aaba8568e1cb86926f2ada9dde7420a421a677a3b9c368c21ecb61598bf47b10", sha256(Protocol.valueSuffix))
        assertEquals("357f09352086efedd9c41d4fae773586c3e07b09b1243f091a7394f727acb324", sha256(Protocol.xorMask.toString()))
    }

    @Test
    fun credentialsShape() {
        val c = Protocol.credentials(uuid, ts)
        assertTrue(c.clientId.startsWith("$uuid\$his\$"))
        assertTrue(c.clientId.endsWith("_vidaacommon_001"))
        assertTrue(c.username.startsWith("his$"))
        assertEquals(32, c.password.length)
    }

    @Test
    fun tokenKeepsIdentityAndSwapsPassword() {
        val plain = Protocol.credentials(uuid, ts)
        val token = Protocol.credentials(uuid, ts, "tok")
        assertEquals(plain.clientId, token.clientId)
        assertEquals(plain.username, token.username)
        assertEquals("tok", token.password)
        assertNotEquals(plain.password, Protocol.credentials(uuid, ts + 1).password)
    }

    @Test
    fun installPayloadShape() {
        val p = Json.parseToJsonElement(Protocol.installPayload("nuviodebug", "Nuvio", "https://example.app/", "")).jsonObject
        assertEquals("app_install", p["type"]!!.jsonPrimitive.content)
        val info = p["app_info"]!!.jsonObject
        assertEquals(setOf("Title", "StoreType", "mediaId", "Id", "Image", "URL", "configUrlDownload", "configUrl"), info.keys)
        assertEquals(99, info["StoreType"]!!.jsonPrimitive.int)
    }

    @Test
    fun parsers() {
        assertEquals("x", Protocol.parseToken("""{"accesstoken":"x","accesstoken_duration_day":2}""")!!.accessToken)
        assertNull(Protocol.parseToken("junk"))
        assertEquals(emptyList<Tile>(), Protocol.parseAppList("[]"))
        assertNull(Protocol.parseAppList("{}"))
        assertEquals(true, Protocol.parsePinResult("""{"result":1}"""))
        assertEquals(false, Protocol.parsePinResult("""{"result":0}"""))
    }
}
