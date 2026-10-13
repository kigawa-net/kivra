// kivra-model-gateway: Model Gateway minimal implementation (design 05-model-gateway.md).
// Independent of kivra-core / DB schema / Run state machine. Audit and metrics are hook interfaces only.
dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.snakeyaml)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
