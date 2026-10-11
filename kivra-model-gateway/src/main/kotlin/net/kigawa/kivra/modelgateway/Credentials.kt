package net.kigawa.kivra.modelgateway

/** Resolves a credential reference (registry `credentialRef`) to its secret value. Values are never logged or committed. */
fun interface CredentialSource { fun get(ref: String): String? }

/** Reads the credential from an environment variable named by the reference. */
class EnvCredentialSource(private val env: Map<String, String> = System.getenv()) : CredentialSource {
    override fun get(ref: String): String? = env[ref]?.takeIf { it.isNotBlank() }
}
