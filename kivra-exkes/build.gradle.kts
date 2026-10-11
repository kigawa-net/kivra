// kivra-exkes: ExecutionPort (concrete types), ExkesAdapter (Ktor client) and FakeExecutionPort (issue #29).
// No DB schema / Run state machine dependency: only the M0 reconciliation contract types in kivra-core.
dependencies {
    api(project(":kivra-core"))
    api(libs.ktor.client.core)
    implementation(libs.kotlinx.serialization.json)

    // Production engine; tests use MockEngine only (never a real exkes).
    runtimeOnly(libs.ktor.client.cio)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
