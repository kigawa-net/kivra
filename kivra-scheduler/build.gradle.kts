// kivra-scheduler: SchedulePort concrete types, FakeSchedulePort and AiSchedulerAdapter (issue #38).
// No DB / Run state machine dependency and no Orchestrator wiring: the Orchestrator (#36) consumes ScheduleTriggerHandler later.
dependencies {
    api(project(":kivra-core"))
    api(libs.kotlinx.coroutines.core)
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
