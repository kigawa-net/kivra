package net.kigawa.kivra.modelgateway

import java.net.URI

/**
 * Resolves the secret for a registry provider. Implementations must not derive the secret location from
 * registry-controlled strings alone (see [BoundCredentialSource]). Values are never logged or committed.
 */
fun interface CredentialSource {
    fun get(provider: ProviderEntry): String?

    /**
     * True when the provider may be called with no credential at all: a LOCAL provider whose endpoint host is fixed by a trusted
     * binding that declares no secret. Always false by default, so every other provider still needs its secret.
     */
    fun unauthenticated(provider: ProviderEntry): Boolean = false
}

/**
 * Trusted-administrator binding: providerId -> (endpoint host, logical credential label, secret env var).
 * Fixed outside the Git-managed registry (deployment config), so editing the registry cannot redirect a
 * secret to another host or point a provider at another secret.
 *
 * [secretEnvVar] null is an unauthenticated binding (an on-premises LOCAL endpoint with no credential). It is honoured only
 * for a provider whose trust zone is LOCAL, and its endpoint may be plain HTTP (cluster-internal) on the bound host.
 * Its [credentialRef] label is still matched against the registry, as for a secret binding.
 */
data class CredentialBinding(
    val providerId: String,
    val allowedHost: String,
    val credentialRef: String,
    val secretEnvVar: String?,
)

/**
 * Releases a secret only when the provider has a binding, the registry `credentialRef` matches the
 * binding's label, and the registry `endpoint` is plain HTTPS (default port, no userinfo) on exactly the
 * bound host. Anything else yields null (fail closed), so the key is never attached to an unapproved target.
 *
 * An unauthenticated binding ([CredentialBinding.secretEnvVar] null) releases no secret; [unauthenticated] is true only when
 * the provider is LOCAL and its endpoint (HTTP or HTTPS, no userinfo) is on exactly the bound host.
 */
class BoundCredentialSource(
    bindings: List<CredentialBinding>,
    private val env: Map<String, String> = System.getenv(),
) : CredentialSource {
    private val byProvider = bindings.associateBy { it.providerId }

    init { require(byProvider.size == bindings.size) { "duplicate credential binding" } }

    override fun get(provider: ProviderEntry): String? {
        val b = bindingFor(provider) ?: return null
        val envVar = b.secretEnvVar ?: return null
        return env[envVar]?.takeIf { it.isNotBlank() }
    }

    override fun unauthenticated(provider: ProviderEntry): Boolean {
        val b = bindingFor(provider) ?: return false
        return b.secretEnvVar == null && provider.trustZone == TrustZone.LOCAL
    }

    /** The binding that applies to [provider], or null when the label or the endpoint does not match it. */
    private fun bindingFor(provider: ProviderEntry): CredentialBinding? {
        val b = byProvider[provider.id] ?: return null
        if (provider.credentialRef != b.credentialRef) return null
        val endpointOk = if (b.secretEnvVar == null) localEndpointAllowed(provider.endpoint, b.allowedHost)
        else endpointAllowed(provider.endpoint, b.allowedHost)
        return if (endpointOk) b else null
    }

    companion object {
        fun endpointAllowed(endpoint: String, allowedHost: String): Boolean {
            val uri = runCatching { URI(endpoint) }.getOrNull() ?: return false
            return uri.scheme.equals("https", ignoreCase = true) &&
                uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443) &&
                uri.host != null && uri.host.equals(allowedHost, ignoreCase = true)
        }

        /** An unauthenticated LOCAL endpoint: HTTP or HTTPS, no userinfo, on exactly the bound host (any port). */
        fun localEndpointAllowed(endpoint: String, allowedHost: String): Boolean {
            val uri = runCatching { URI(endpoint) }.getOrNull() ?: return false
            val scheme = uri.scheme ?: return false
            return (scheme.equals("https", ignoreCase = true) || scheme.equals("http", ignoreCase = true)) &&
                uri.userInfo == null &&
                uri.host != null && uri.host.equals(allowedHost, ignoreCase = true)
        }
    }
}
