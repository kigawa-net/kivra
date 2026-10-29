package net.kigawa.kivra.ui.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Ktor HTTP client for the Kivra API.
 */
object ApiClient {

    val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    suspend fun getRuns(scope: String = "mine"): List<ApiRun> {
        return try {
            client.get("/api/runs?scope=$scope") {
            }.body<List<ApiRun>>()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getRun(runId: String): ApiRun? {
        return try {
            client.get("/api/runs/$runId") {
            }.body<ApiRun>()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun submitTask(title: String, body: String? = null, kind: String = "SIMULATED"): Boolean {
        return try {
            val response = client.post("/api/tasks") {
                setBody(mapOf("title" to title, "kind" to kind, "body" to (body ?: "")))
            }
            response.status.value in 200..299
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getProjects(): List<ApiProject> {
        return try {
            client.get("/api/projects") {
            }.body<List<ApiProject>>()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun createProject(name: String, description: String? = null): Boolean {
        return try {
            val response = client.post("/api/projects") {
                setBody(mapOf("name" to name, "description" to (description ?: "")))
            }
            response.status.value in 200..299
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getGoals(): List<ApiGoal> {
        return try {
            client.get("/api/goals") {
            }.body<List<ApiGoal>>()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getOrders(): List<ApiOrder> {
        return try {
            client.get("/api/orders") {
            }.body<List<ApiOrder>>()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getMemories(): List<ApiMemory> {
        return try {
            client.get("/api/memories") {
            }.body<List<ApiMemory>>()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getBudget(): ApiBudget? {
        return try {
            client.get("/api/budget") {
            }.body<ApiBudget>()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getAssignees(): List<ApiAssignee> {
        return try {
            client.get("/api/assignees") {
            }.body<List<ApiAssignee>>()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getApprovals(): List<ApiOrder> {
        return try {
            client.get("/api/approvals") {
            }.body<List<ApiOrder>>()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
