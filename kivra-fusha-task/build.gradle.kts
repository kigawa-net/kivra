// kivra-fusha-task: TaskProvider adapter for fusha-task (design #2). Read-only MVP (issue #27).
dependencies {
    api(project(":kivra-core"))
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
