// kivra-agent: the local agent that will run coding attempts on the user's PC (ADR 0010, design #187 section 7).
// PR 5 covers only the kivra communication (config, Keycloak token, claim, heartbeat, status, fail, stop). Docker, the Claude
// Code CLI and the diff come in PR 6-8. No server dependency (no DB, Ktor server, Exposed, Flyway): the agent keeps its own
// copies of the wire shapes, and does not depend on kivra-core yet.
plugins {
    application
}

application {
    mainClass.set("net.kigawa.kivra.agent.MainKt")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
