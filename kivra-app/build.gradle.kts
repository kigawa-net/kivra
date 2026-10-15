// kivra-app: Ktor application, configuration, Flyway migrations, Exposed tables and repositories.
plugins {
    application
    alias(libs.plugins.ktor)
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
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
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
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
