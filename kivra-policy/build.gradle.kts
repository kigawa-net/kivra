// kivra-policy: Tool Gateway and static Policy Engine (design #1 section 6).
// Pure domain code: no DB, no Run state machine, no persistence. Approval/Audit are ports only (#33 implements them).
dependencies {
    implementation(libs.kotlinx.serialization.json)
    // Only for logLoaded(logger). Version matches the slf4j pulled in by kivra-app's logback 1.5.x.
    implementation("org.slf4j:slf4j-api:2.0.17")
}
