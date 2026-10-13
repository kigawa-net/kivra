// kivra-memory: MemoryProvider port, Working Memory, degradation layer, Fake and the recl REST adapter skeleton
// (design #6, issue #37). recl has no implementation yet: every recl-facing contract is 要確認.
plugins {
    `java-test-fixtures`
}

dependencies {
    api(project(":kivra-core"))
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)

    // Shared Contract tests for every MemoryMutationJournalPort implementation (also used by kivra-app's M0 adapter).
    testFixturesApi(libs.kotlin.test.junit5)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
