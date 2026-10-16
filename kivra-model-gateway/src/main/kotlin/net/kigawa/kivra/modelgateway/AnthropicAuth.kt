package net.kigawa.kivra.modelgateway

/**
 * The credential attached to one Anthropic request. [secrets] are the values that must be redacted from any error text.
 * [toString] never shows the header value.
 */
class AnthropicAuthorization(val headerName: String, val headerValue: String, val secrets: List<String>) {
    override fun toString(): String = "AnthropicAuthorization($headerName=***)"
}

/**
 * How an [AnthropicProvider] authenticates. Two implementations: [StaticKeyAuth] (an API key in `x-api-key`) and
 * [AnthropicWifAuth] (Workload Identity Federation, a Bearer access token exchanged from a projected ServiceAccount JWT).
 */
interface AnthropicAuth {
    /** Local check only (no network call): whether a credential can be released for [target]. */
    fun isConfigured(target: ResolvedTarget): Boolean

    /** The header for one request. Throws a [GatewayException] with a fixed message when none can be produced. */
    suspend fun authorize(target: ResolvedTarget): AnthropicAuthorization

    /** Called after the API answered 401 with [authorization]: drop the cached secret that produced it. Never retries. */
    fun onUnauthorized(authorization: AnthropicAuthorization) {}
}

/** The static API key path: the key is released by the bound [CredentialSource] and sent as `x-api-key`. */
class StaticKeyAuth(private val credentials: CredentialSource) : AnthropicAuth {
    override fun isConfigured(target: ResolvedTarget): Boolean = credentials.get(target.provider) != null

    override suspend fun authorize(target: ResolvedTarget): AnthropicAuthorization {
        val key = credentials.get(target.provider)
            ?: throw GatewayException(GatewayError(ErrorKind.AUTH, "credential '${target.provider.credentialRef}' is not configured", code = "NO_CREDENTIAL", sendState = SendState.NOT_SENT))
        return AnthropicAuthorization("x-api-key", key, listOf(key))
    }
}
