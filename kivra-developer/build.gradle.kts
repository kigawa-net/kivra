// kivra-developer: DeveloperPort (issue #56, ADR 0008 part 3). An LLM proposes a PatchSet through ModelGateway.
// No DB, GitHub or kivra-app dependency. Not wired into production (ADR 0008 part 6).
dependencies {
    api(project(":kivra-core"))
    api(project(":kivra-model-gateway"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.logback.classic)
}
