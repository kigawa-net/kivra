package net.kigawa.kivra.github

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Mints an installation token with the App JWT: POST /app/installations/{installation_id}/access_tokens.
 *
 * The request is scoped to the single repository [repositoryName] and to [permissions] only. Failures throw a
 * [GitHubTokenUnavailableException] with a fixed message: the JWT, the response body and the token are never copied into
 * an exception.
 */
class GitHubAppTokenMinter(
    private val client: HttpClient,
    private val apiBase: String = "https://api.github.com",
    private val repositoryName: String = "kivra",
    private val permissions: Map<String, String> = linkedMapOf("contents" to "write", "pull_requests" to "write"),
) : InstallationTokenMinter {
    override suspend fun mint(appJwt: String, installationId: Long): InstallationToken {
        require(installationId > 0) { "installation id must be positive" }
        val body = JsonObject(
            linkedMapOf(
                "repositories" to JsonArray(listOf(JsonPrimitive(repositoryName))),
                "permissions" to JsonObject(permissions.mapValues { JsonPrimitive(it.value) }),
            ),
        ).toString()
        val url = apiBase.trimEnd('/') + "/app/installations/$installationId/access_tokens"
        val text: String
        val status: Int
        try {
            val resp = client.request(url) {
                method = HttpMethod.Post
                header("Authorization", "Bearer $appJwt")
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            status = resp.status.value
            text = resp.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw GitHubTokenUnavailableException("GitHub App installation token request failed (transport)")
        }
        if (status !in 200..299) {
            throw GitHubTokenUnavailableException("GitHub App installation token request failed (HTTP $status)")
        }
        return parse(text)
    }

    private fun parse(text: String): InstallationToken {
        val fail = GitHubTokenUnavailableException("GitHub App installation token response is malformed")
        val obj = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            throw fail
        }
        val token = obj["token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw fail
        val expiresAt = try {
            Instant.parse(obj["expires_at"]?.jsonPrimitive?.contentOrNull ?: throw fail)
        } catch (_: Exception) {
            throw fail
        }
        return InstallationToken(token, expiresAt)
    }
}
