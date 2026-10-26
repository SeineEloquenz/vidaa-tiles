package nz.eloque.vidaa_tiles.tv

import android.content.Context
import androidx.core.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Session(
    val host: String,
    val deviceUuid: String,
    val accessToken: String,
    val refreshToken: String,
    val accessDays: Long = 2,
    val refreshDays: Long = 30,
    /** Local wall-clock expiry in epoch millis, so TV clock skew cannot affect it. */
    val expiresAt: Long = 0,
    val pairFailures: Int = 0,
) {
    fun withGrant(grant: TokenGrant): Session {
        val days = grant.accessDays?.takeIf { it > 0 } ?: accessDays
        return copy(
            accessToken = grant.accessToken,
            refreshToken = grant.refreshToken ?: refreshToken,
            accessDays = days,
            refreshDays = grant.refreshDays?.takeIf { it > 0 } ?: refreshDays,
            expiresAt = System.currentTimeMillis() + days * 86_400_000,
        )
    }
}

class SessionStore(
    context: Context,
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "session.json"))
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Session? =
        runCatching {
            json.decodeFromString<Session>(file.readFully().decodeToString())
        }.getOrNull()?.takeIf { it.host.isNotEmpty() && it.accessToken.isNotEmpty() && it.refreshToken.isNotEmpty() }

    fun save(session: Session) {
        val out = file.startWrite()
        try {
            out.write(json.encodeToString(session).toByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    fun clear() = file.delete()
}
