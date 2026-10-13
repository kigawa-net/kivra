// kivra-github: GitHub Adapter (issue #34). Port + HTTP adapter only; no Tool Gateway wiring, no DB dependency.
dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
