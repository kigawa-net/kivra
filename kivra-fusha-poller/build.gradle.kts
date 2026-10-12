// kivra-fusha-poller: read-only Poller, change detection and internal TaskEvent normalization (issue #28, M1).
// Depends only on the TaskProvider port (kivra-core), not on the fusha-task adapter. No writes (Writeback is out of scope).
dependencies {
    api(project(":kivra-core"))

    testImplementation(libs.kotlinx.coroutines.test)
}
