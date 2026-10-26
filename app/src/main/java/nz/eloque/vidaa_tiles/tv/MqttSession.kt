package nz.eloque.vidaa_tiles.tv

import kotlinx.coroutines.delay
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.Collections
import javax.net.ssl.SSLSocketFactory

/** One connection to the TV's MQTT broker, collecting every message it receives. */
class MqttSession(
    host: String,
    deviceUuid: String,
    tvTimestamp: Long,
    token: String?,
    private val socketFactory: SSLSocketFactory,
) : AutoCloseable {
    val credentials = Protocol.credentials(deviceUuid, tvTimestamp, token)
    val topics = Topics(credentials.clientId)

    private val messages: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private val client = MqttClient("ssl://$host:$PORT", credentials.clientId, MemoryPersistence())

    val messageCount: Int get() = messages.size

    /** Returns 0 on success, or the broker's refusal code (4 bad credentials, 5 not authorised). */
    fun connect(): Int {
        client.setCallback(
            object : MqttCallback {
                override fun connectionLost(cause: Throwable?) = Unit

                override fun messageArrived(
                    topic: String,
                    message: MqttMessage,
                ) {
                    messages += topic to String(message.payload)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            },
        )
        val options =
            MqttConnectOptions().apply {
                mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
                isCleanSession = true
                isAutomaticReconnect = false
                keepAliveInterval = 30
                connectionTimeout = 10
                userName = credentials.username
                password = credentials.password.toCharArray()
                socketFactory = this@MqttSession.socketFactory
                isHttpsHostnameVerificationEnabled = false
            }
        return try {
            client.connect(options)
            0
        } catch (e: MqttException) {
            when (e.reasonCode) {
                MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt(),
                MqttException.REASON_CODE_NOT_AUTHORIZED.toInt(),
                MqttException.REASON_CODE_INVALID_CLIENT_ID.toInt(),
                MqttException.REASON_CODE_BROKER_UNAVAILABLE.toInt(),
                -> e.reasonCode
                else -> throw e
            }
        }
    }

    fun subscribe(vararg topicFilters: String) {
        client.subscribe(topicFilters, IntArray(topicFilters.size))
    }

    fun publish(
        topic: String,
        payload: String = "",
    ) {
        client.publish(topic, payload.toByteArray(), 0, false)
    }

    suspend fun waitFor(
        suffix: String,
        timeoutMs: Long,
        since: Int = 0,
    ): String? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            synchronized(messages) {
                messages.drop(since).firstOrNull { it.first.endsWith(suffix) }
            }?.let { return it.second }
            delay(300)
        }
        return null
    }

    override fun close() {
        runCatching { if (client.isConnected) client.disconnect(2000) }
        runCatching { client.close() }
    }

    private companion object {
        const val PORT = 36669
    }
}
