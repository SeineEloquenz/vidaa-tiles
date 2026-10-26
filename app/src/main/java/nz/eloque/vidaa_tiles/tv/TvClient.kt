package nz.eloque.vidaa_tiles.tv

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

class AuthException(
    message: String,
) : Exception(message)

/** Pairing, token lifecycle and tile actions against a VIDAA TV, ported from Sidee's `core/client.py`. */
class TvClient(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val discovery = Discovery(appContext)
    private val store = SessionStore(appContext)
    private val socketFactory by lazy { ClientCertificate.socketFactory(appContext) }
    private val mutex = Mutex()

    fun currentSession(): Session? = store.load()

    fun forget() = store.clear()

    suspend fun discover(): List<TvInfo> = withContext(Dispatchers.IO) { discovery.discover() }

    suspend fun pair(
        host: String,
        pinProvider: suspend () -> String,
    ): Session =
        io {
            val reused = store.load()?.takeIf { it.pairFailures < MAX_PAIR_FAILURES }
            val deviceUuid = reused?.deviceUuid ?: Protocol.newDeviceUuid()
            try {
                session(host, deviceUuid, token = null).use { mqtt ->
                    val rc = mqtt.connect()
                    if (rc != 0) throw IOException("Connection refused by the TV (rc=$rc)")
                    val since = mqtt.messageCount
                    mqtt.subscribe(
                        mqtt.topics.mobile + "ui_service/data/authentication",
                        mqtt.topics.mobile + "ui_service/data/authenticationcode",
                        mqtt.topics.tokenReply,
                    )
                    delay(300)
                    mqtt.publish(mqtt.topics.ui + "actions/vidaa_app_connect", Protocol.pairConnectPayload())
                    mqtt.waitFor("data/authentication", 15_000, since)
                        ?: throw IOException("The TV did not display a PIN")

                    val pin =
                        pinProvider().trim().toIntOrNull()
                            ?: throw IllegalArgumentException("Enter the PIN shown on the TV")
                    val sincePin = mqtt.messageCount
                    mqtt.publish(mqtt.topics.ui + "actions/authenticationcode", Protocol.pairPinPayload(pin))
                    val reply =
                        mqtt.waitFor("ui_service/data/authenticationcode", 15_000, sincePin)
                            ?: throw IOException("The TV did not confirm the PIN. Request a new code.")
                    when (Protocol.parsePinResult(reply)) {
                        null -> throw IOException("Invalid PIN response from the TV")
                        false -> throw AuthException("PIN expired or incorrect. Request a new code.")
                        true -> Unit
                    }

                    mqtt.publish(mqtt.topics.platform + "data/gettoken", Protocol.tokenRequestPayload(""))
                    mqtt.publish(mqtt.topics.ui + "actions/authenticationcodeclose")
                    val grant =
                        mqtt.waitFor("platform_service/data/tokenissuance", 60_000, sincePin)?.let(Protocol::parseToken)
                            ?: throw IOException("PIN accepted, but the TV did not send a token. Request a new code.")
                    Session(host, deviceUuid, grant.accessToken, grant.refreshToken ?: "").withGrant(grant).also {
                        if (it.refreshToken.isEmpty()) throw AuthException("The TV sent an incomplete token")
                        store.save(it)
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) reused?.let { store.save(it.copy(pairFailures = it.pairFailures + 1)) }
                throw e
            }
        }

    suspend fun listTiles(): List<Tile> =
        withSession { mqtt ->
            mqtt.subscribe(mqtt.topics.applistReply)
            delay(300)
            requestAppList(mqtt)
        }

    suspend fun addTile(
        appId: String,
        name: String,
        url: String,
        image: String,
    ): List<Tile> =
        withSession { mqtt ->
            mqtt.subscribe(mqtt.topics.applistReply)
            delay(300)
            mqtt.publish(mqtt.topics.ui + "actions/uievent", Protocol.installPayload(appId, name, url, image))
            delay(8000)
            var apps = emptyList<Tile>()
            repeat(2) { attempt ->
                apps = requestAppList(mqtt)
                if (apps.any { it.appId.equals(appId, ignoreCase = true) && it.url == url }) return@withSession apps
                if (attempt == 0) delay(2000)
            }
            apps
        }

    suspend fun launchTile(
        appId: String,
        name: String,
        url: String,
    ) = withSession { mqtt ->
        mqtt.publish(mqtt.topics.ui + "actions/launchapp", Protocol.launchPayload(appId, name, url))
        delay(1000)
    }

    private suspend fun requestAppList(mqtt: MqttSession): List<Tile> {
        val since = mqtt.messageCount
        mqtt.publish(mqtt.topics.ui + "actions/applist", "0")
        return mqtt.waitFor("ui_service/data/applist", 8000, since)?.let(Protocol::parseAppList).orEmpty()
    }

    private suspend fun <T> withSession(op: suspend (MqttSession) -> T): T =
        io {
            var session = store.load() ?: throw AuthException("No TV paired yet")
            if (System.currentTimeMillis() >= session.expiresAt - REFRESH_MARGIN_MS) session = refresh(session)
            repeat(2) { attempt ->
                session(session.host, session.deviceUuid, session.accessToken).use { mqtt ->
                    val rc =
                        try {
                            mqtt.connect()
                        } catch (e: Exception) {
                            if (attempt == 1) throw e
                            null
                        }
                    if (rc == 0) return@io op(mqtt)
                    if (attempt == 1) throw AuthException("Connection refused by the TV (rc=$rc). Pair again.")
                }
                session = refresh(session)
            }
            error("unreachable")
        }

    private suspend fun refresh(session: Session): Session {
        val grant =
            session(session.host, session.deviceUuid, session.refreshToken).use { mqtt ->
                val rc = mqtt.connect()
                if (rc == 4 || rc == 5) throw AuthException("The TV no longer recognises this device. Pair again.")
                if (rc != 0) throw IOException("TV unreachable during token refresh (rc=$rc)")
                mqtt.subscribe(mqtt.topics.tokenReply)
                delay(300)
                mqtt.publish(mqtt.topics.platform + "data/gettoken", Protocol.tokenRequestPayload(session.refreshToken))
                mqtt.waitFor("platform_service/data/tokenissuance", 20_000)?.let(Protocol::parseToken)
            } ?: throw AuthException("No token received during refresh. Pair again.")
        return session.withGrant(grant).also(store::save)
    }

    private fun session(
        host: String,
        deviceUuid: String,
        token: String?,
    ) = MqttSession(host, deviceUuid, discovery.tvTimestamp(host), token, socketFactory)

    private suspend fun <T> io(block: suspend () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }

    private companion object {
        const val MAX_PAIR_FAILURES = 2
        const val REFRESH_MARGIN_MS = 12 * 3600 * 1000L
    }
}
