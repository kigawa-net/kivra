package net.kigawa.kivra.agent

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A bearer token. [reveal] is for the Authorization header only; [toString] shows no value. */
class AccessToken(private val value: String) {
    fun reveal(): String = value

    override fun toString(): String = "AccessToken(***)"
}

/** Supplies the bearer token for one request. */
interface TokenSource {
    /** The current token: a cached one while it is well before expiry, a new one otherwise. */
    suspend fun token(): AccessToken

    /** Drops the cached token, for example after kivra answered 401. The next [token] call fetches a new one. */
    fun invalidate()
}

/**
 * Client-credentials token source for Keycloak (design #187 section 4.1). Each fetch is a new token request (a new `jti`), never a
 * reuse of an old token. A token is cached only until [refreshSkew] before its expiry. The client secret is read from
 * [clientSecretFile] at each fetch (so it can be rotated in place) and is sent only in the form body. It never goes into a log,
 * an exception or [toString].
 */
class KeycloakTokenSource(
    private val http: HttpClient,
    private val tokenUrl: String,
    private val clientId: String,
    private val clientSecretFile: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val refreshSkew: Duration = Duration.ofSeconds(60),
) : TokenSource {
    private val mutex = Mutex()

    @Volatile
    private var cached: Cached? = null

    private class Cached(val token: AccessToken, val refreshAt: Instant)

    override suspend fun token(): AccessToken = mutex.withLock {
        val now = clock.instant()
        cached?.takeIf { now.isBefore(it.refreshAt) }?.let { return it.token }
        val (token, expiresIn) = fetch()
        cached = Cached(token, now.plusSeconds(expiresIn).minus(refreshSkew))
        token
    }

    override fun invalidate() {
        cached = null
    }

    private suspend fun fetch(): Pair<AccessToken, Long> {
        val secret = readSecret()
        val reply = try {
            http.submitForm(
                url = tokenUrl,
                formParameters = Parameters.build {
                    append("grant_type", "client_credentials")
                    append("client_id", clientId)
                    append("client_secret", secret)
                },
            ).let { it.status.value to it.bodyAsText() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AgentApiException(if (e.isTimeout()) AgentApiErrorKind.TIMEOUT else AgentApiErrorKind.TOKEN_UNAVAILABLE)
        }
        val (status, body) = reply
        if (status == 400 || status == 401) throw AgentApiException(AgentApiErrorKind.TOKEN_REJECTED)
        if (status !in 200..299) throw AgentApiException(AgentApiErrorKind.TOKEN_UNAVAILABLE)
        val parsed = try {
            JSON.decodeFromString<TokenBody>(body)
        } catch (e: Exception) {
            throw AgentApiException(AgentApiErrorKind.MALFORMED_RESPONSE)
        }
        if (parsed.accessToken.isBlank() || parsed.accessToken.length > MAX_TOKEN_CHARS || parsed.expiresIn <= 0) {
            throw AgentApiException(AgentApiErrorKind.MALFORMED_RESPONSE)
        }
        return AccessToken(parsed.accessToken) to parsed.expiresIn
    }

    private suspend fun readSecret(): String {
        val value = try {
            // A few bytes from a local file: read in place, no dispatcher hop.
            Files.readString(clientSecretFile).trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AgentApiException(AgentApiErrorKind.SECRET_UNREADABLE)
        }
        if (value.isEmpty()) throw AgentApiException(AgentApiErrorKind.SECRET_UNREADABLE)
        return value
    }

    @Serializable
    private class TokenBody(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Long,
    )

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        const val MAX_TOKEN_CHARS = 16_384
    }
}
