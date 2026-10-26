// kivra-app: Ktor application, configuration, Flyway migrations, Exposed tables and repositories.
plugins {
    application
    alias(libs.plugins.ktor)
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

// CI の test-mariadb を 3 シャード(並列ジョブ)に分ける。KIVRA_TEST_SHARD が未設定なら全テスト(既定、ローカルと test-h2 は従来どおり)。
// 1: db.*  2: selfdev.* と mcp.*  3: 上の 1 と 2 を除く残りすべて(新しいパッケージは自動で 3 に入る)。
val kivraTestShardPackages = mapOf(
    "1" to listOf("net.kigawa.kivra.db.*"),
    "2" to listOf("net.kigawa.kivra.selfdev.*", "net.kigawa.kivra.mcp.*"),
)
tasks.named<Test>("test") {
    val shard = System.getenv("KIVRA_TEST_SHARD")?.trim()?.takeIf { it.isNotEmpty() }
    when (shard) {
        null -> Unit
        "1", "2" -> filter {
            kivraTestShardPackages.getValue(shard).forEach { includeTestsMatching(it) }
        }
        "3" -> filter {
            kivraTestShardPackages.values.flatten().forEach { excludeTestsMatching(it) }
        }
        else -> throw GradleException("KIVRA_TEST_SHARD は 1, 2, 3 のいずれか、または未設定(全テスト)です: '$shard'")
    }
}

dependencies {
    implementation(project(":kivra-core"))
    implementation(project(":kivra-observability"))
    implementation(project(":kivra-exkes"))
    implementation(project(":kivra-memory"))
    implementation(project(":kivra-policy"))
    implementation(project(":kivra-fusha-task"))
    implementation(project(":kivra-fusha-poller"))
    // Model Gateway budget port. The durable store (db/DbBudgetStore) implements it; the gateway itself never imports kivra-app.
    implementation(project(":kivra-model-gateway"))
    // Self-development runner (ADR 0008 part 4, issue #56): the Developer port and the GitHub port. Not wired into Application.kt yet (part 6).
    implementation(project(":kivra-developer"))
    implementation(project(":kivra-github"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    // Run execution monitor stream (ADR 0012 PR 3). Only the SSE plugin is added; no new Gradle module.
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    // OIDC session persistence (login session)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.call.id)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.hikari.cp)
    implementation(libs.flyway.core)
    implementation(libs.flyway.mysql)
    implementation(libs.mariadb.driver)

    // Task API の JWT 検証（KIVRA_AUTH_MODE=oidc-jwt）。kivra-app だけに閉じる。
    implementation(libs.java.jwt)
    implementation(libs.jwks.rsa)

    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    testImplementation(libs.ktor.server.test.host)
    // Acceptance harness (issue #70): the exkes fake sits behind a MockEngine (FaultProxy). Test scope only.
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.h2)
    // Shared MemoryMutationJournalPort Contract, run against the M0 adapter (Issue #91).
    testImplementation(testFixtures(project(":kivra-memory")))
}
