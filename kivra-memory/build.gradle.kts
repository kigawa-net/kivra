// kivra-memory: MemoryProvider port, Working Memory, degradation layer, Fake and the recl REST adapter skeleton
// (design #6, issue #37). recl has no implementation yet: every recl-facing contract is 要確認.
dependencies {
    api(project(":kivra-core"))
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
