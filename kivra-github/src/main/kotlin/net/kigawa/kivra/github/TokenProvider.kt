package net.kigawa.kivra.github

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Supplies a GitHub installation token. The Adapter never sees or stores long-lived credentials; the App private
 * key is referenced from configuration (a path or secret reference) and loaded only by a [JwtSigner]. No key
 * material is committed to the repository.
 */
interface TokenProvider {
    suspend fun token(): String

    /** Called after a 401 so a cached token is not reused. */
    fun invalidate() {}
}

/** Fixed token, for tests and local use only. */
class StaticTokenProvider(private val value: String) : TokenProvider {
    override suspend fun token(): String = value
}

/** Signs the short-lived (max 10 min) GitHub App JWT. Pluggable so a KMS/secret-manager signer can replace the local one. */
fun interface JwtSigner {
    fun sign(appId: String, now: Instant): String
}

/**
 * RS256 signer from a PKCS#8 PEM. GitHub issues PKCS#1 keys ("BEGIN RSA PRIVATE KEY"); convert with
 * `openssl pkcs8 -topk8 -nocrypt` first (confirmation needed on the final key delivery method, issue #34).
 * [pem] is read by the caller from a configured path; never log or commit it.
 */
class RsaJwtSigner(pem: String) : JwtSigner {
    private val key = KeyFactory.getInstance("RSA").generatePrivate(
        PKCS8EncodedKeySpec(
            Base64.getMimeDecoder().decode(
                pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", ""),
            ),
        ),
    )

    override fun sign(appId: String, now: Instant): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val payload = enc.encodeToString(
            """{"iat":${now.epochSecond - 60},"exp":${now.epochSecond + 9 * 60},"iss":"$appId"}""".toByteArray(),
        )
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(key)
            update("$header.$payload".toByteArray())
            sign()
        }
        return "$header.$payload.${enc.encodeToString(sig)}"
    }
}

/** An installation token with its expiry, as minted by the App. */
data class InstallationToken(val token: String, val expiresAt: Instant)

/** Mints installation tokens (POST /app/installations/{id}/access_tokens). Abstracted so it can be faked. */
fun interface InstallationTokenMinter {
    suspend fun mint(appJwt: String, installationId: Long): InstallationToken
}

/** Caches an installation token and refreshes it [refreshMargin] before expiry. */
class InstallationTokenProvider(
    private val appId: String,
    private val installationId: Long,
    private val signer: JwtSigner,
    private val minter: InstallationTokenMinter,
    private val clock: Clock = Clock.systemUTC(),
    private val refreshMarginSeconds: Long = 120,
) : TokenProvider {
    private val lock = Mutex()

    @Volatile
    private var cached: InstallationToken? = null

    override suspend fun token(): String = lock.withLock {
        val now = clock.instant()
        cached?.takeIf { it.expiresAt.minusSeconds(refreshMarginSeconds).isAfter(now) }?.let { return it.token }
        val fresh = minter.mint(signer.sign(appId, now), installationId)
        cached = fresh
        fresh.token
    }

    override fun invalidate() {
        cached = null
    }
}
