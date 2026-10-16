package net.kigawa.kivra.github

import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Supplies the token used for one GitHub request. The Adapter asks for a token per request and never stores one itself.
 * Implementations: [StaticTokenProvider] (a PAT, for compatibility) and [InstallationTokenProvider] (a GitHub App,
 * recommended). The App private key is loaded only by a [JwtSigner]; no key material is committed to the repository.
 */
interface TokenProvider {
    suspend fun token(): String

    /** Called after a 401 so a cached token is not reused. */
    fun invalidate() {}
}

/** A fixed token: a personal access token supplied by configuration. [toString] never shows it. */
class StaticTokenProvider(private val value: String) : TokenProvider {
    override suspend fun token(): String = value

    override fun toString(): String = "StaticTokenProvider(***)"
}

/**
 * The token could not be obtained. The message is fixed: it never carries the JWT, the key, the token or a response body,
 * so it is safe to log or to show.
 */
class GitHubTokenUnavailableException(message: String) : RuntimeException(message)

/** Signs the short-lived (max 10 min) GitHub App JWT. Pluggable so a KMS/secret-manager signer can replace the local one. */
fun interface JwtSigner {
    fun sign(appId: String, now: Instant): String
}

/**
 * RS256 signer from a PEM private key. Accepts PKCS#8 ("BEGIN PRIVATE KEY") and PKCS#1 ("BEGIN RSA PRIVATE KEY", the
 * format GitHub issues); PKCS#1 is wrapped into PKCS#8 here with the JDK only. Encrypted keys are refused.
 * [pem] is read by the caller from a configured file or secret; it is never logged, printed or committed.
 */
class RsaJwtSigner(pem: String) : JwtSigner {
    private val key: PrivateKey = try {
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8Der(pem)))
    } catch (_: GeneralSecurityException) {
        throw IllegalArgumentException(KEY_REJECTED)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException(KEY_REJECTED)
    }

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

    private companion object {
        const val KEY_REJECTED = "GitHub App private key is not a usable RSA private key (PKCS#1 or PKCS#8, unencrypted)"

        private val MARKER = Regex("-----(BEGIN|END) [A-Z ]+-----")
        private val BEGIN = Regex("-----BEGIN ([A-Z ]+)-----")

        /** The DER of a PKCS#8 PrivateKeyInfo: PKCS#1 keys are wrapped, PKCS#8 keys are used as they are. */
        fun pkcs8Der(pem: String): ByteArray {
            val kind = BEGIN.find(pem)?.groupValues?.get(1)
            val der = Base64.getMimeDecoder().decode(pem.replace(MARKER, ""))
            return when (kind) {
                "PRIVATE KEY" -> der
                "RSA PRIVATE KEY" -> wrapPkcs1(der)
                else -> throw IllegalArgumentException(KEY_REJECTED)
            }
        }

        /** PrivateKeyInfo { version 0, rsaEncryption AlgorithmIdentifier (NULL params), privateKey OCTET STRING(pkcs1) }. */
        fun wrapPkcs1(pkcs1: ByteArray): ByteArray {
            val version = byteArrayOf(0x02, 0x01, 0x00)
            val rsaAlgorithm = byteArrayOf(
                0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00,
            )
            return der(0x30, version + rsaAlgorithm + der(0x04, pkcs1))
        }

        fun der(tag: Int, content: ByteArray): ByteArray {
            val length = if (content.size < 0x80) {
                byteArrayOf(content.size.toByte())
            } else {
                val be = BigInteger.valueOf(content.size.toLong()).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
                byteArrayOf((0x80 or be.size).toByte()) + be
            }
            return byteArrayOf(tag.toByte()) + length + content
        }
    }
}

/** An installation token with its expiry, as minted by the App. */
class InstallationToken(val token: String, val expiresAt: Instant) {
    override fun toString(): String = "InstallationToken(***)"
}

/** Mints installation tokens (POST /app/installations/{id}/access_tokens). Abstracted so it can be faked. */
fun interface InstallationTokenMinter {
    suspend fun mint(appJwt: String, installationId: Long): InstallationToken
}

/**
 * Caches an installation token and refreshes it [refreshMarginSeconds] before expiry (default 5 minutes). Concurrent
 * callers share one refresh: the mutex is held across the mint, so the others wait and then read the new cache entry.
 */
class InstallationTokenProvider(
    private val appId: String,
    private val installationId: Long,
    private val signer: JwtSigner,
    private val minter: InstallationTokenMinter,
    private val clock: Clock = Clock.systemUTC(),
    private val refreshMarginSeconds: Long = 300,
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

    override fun toString(): String = "InstallationTokenProvider(***)"
}
