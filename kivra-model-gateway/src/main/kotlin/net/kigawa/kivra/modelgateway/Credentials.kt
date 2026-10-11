package net.kigawa.kivra.modelgateway

import java.net.URI

/**
 * Resolves the secret for a registry provider. Implementations must not derive the secret location from
 * registry-controlled strings alone (see [BoundCredentialSource]). Values are never logged or committed.
 */
fun interface CredentialSource { fun get(provider: ProviderEntry): String? }

/**
 * Trusted-administrator binding: providerId -> (endpoint host, logical credential label, secret env var).
 * Fixed outside the Git-managed registry (deployment config), so editing the registry cannot redirect a
 * secret to another host or point a provider at another secret.
 */
data class CredentialBinding(
    val providerId: String,
    val allowedHost: String,
    val credentialRef: String,
    val secretEnvVar: String,
)

/**
 * Releases a secret only when the provider has a binding, the registry `credentialRef` matches the
 * binding's label, and the registry `endpoint` is plain HTTPS (default port, no userinfo) on exactly the
 * bound host. Anything else yields null (fail closed), so the key is never attached to an unapproved target.
 */
class BoundCredentialSource(
    bindings: List<CredentialBinding>,
    private val env: Map<String, String> = System.getenv(),
) : CredentialSource {
    private val byProvider = bindings.associateBy { it.providerId }

    init { require(byProvider.size == bindings.size) { "duplicate credential binding" } }

    override fun get(provider: ProviderEntry): String? {
        val b = byProvider[provider.id] ?: return null
        if (provider.credentialRef != b.credentialRef) return null
        if (!endpointAllowed(provider.endpoint, b.allowedHost)) return null
        return env[b.secretEnvVar]?.takeIf { it.isNotBlank() }
    }

    companion object {
        fun endpointAllowed(endpoint: String, allowedHost: String): Boolean {
            val uri = runCatching { URI(endpoint) }.getOrNull() ?: return false
            return uri.scheme.equals("https", ignoreCase = true) &&
                uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443) &&
                uri.host != null && uri.host.equals(allowedHost, ignoreCase = true)
        }
    }
}
