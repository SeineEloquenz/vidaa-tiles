package nz.eloque.vidaa_tiles.tv

import android.annotation.SuppressLint
import android.content.Context
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.zip.Inflater
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** TLS client identity of the official VIDAA remote app, decoded from Sidee's bundle. */
object ClientCertificate {
    private const val ASSET = "tv-client.bundle"
    private const val CERT_END = "-----END CERTIFICATE-----"

    fun socketFactory(context: Context): SSLSocketFactory {
        val pem = decodeBundle(context.assets.open(ASSET).use { it.readBytes() })
        val split = pem.indexOf(CERT_END) + CERT_END.length
        val certificate =
            CertificateFactory
                .getInstance("X.509")
                .generateCertificate(pem.substring(0, split).byteInputStream()) as X509Certificate
        val key = parsePrivateKey(pem.substring(split))

        val keyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        keyStore.setKeyEntry("client", key, CharArray(0), arrayOf(certificate))
        val keyManagers =
            KeyManagerFactory
                .getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(keyStore, CharArray(0)) }
                .keyManagers
        return SSLContext
            .getInstance("TLS")
            .apply { init(keyManagers, arrayOf(TrustAnyTv), null) }
            .socketFactory
    }

    internal fun decodeBundle(blob: ByteArray): String {
        val compressed = Base85.decode(String(blob, Charsets.US_ASCII)).xor("k3".toByteArray())
        val inflater = Inflater().apply { setInput(compressed) }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            check(n > 0 || !inflater.needsInput()) { "Truncated client bundle" }
            out.write(buffer, 0, n)
        }
        inflater.end()
        return out.toString(Charsets.US_ASCII.name())
    }

    private fun parsePrivateKey(pem: String): PrivateKey {
        val der =
            Base64.getMimeDecoder().decode(
                pem
                    .substringAfter("-----BEGIN PRIVATE KEY-----")
                    .substringBefore("-----END PRIVATE KEY-----"),
            )
        val spec = PKCS8EncodedKeySpec(der)
        return listOf("RSA", "EC").firstNotNullOf { algorithm ->
            runCatching { KeyFactory.getInstance(algorithm).generatePrivate(spec) }.getOrNull()
        }
    }

    // The TV presents a self-signed certificate per device, so there is nothing to pin against.
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object TrustAnyTv : X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) = Unit

        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
