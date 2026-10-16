import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
}

// Captured outside subprojects { }: the `libs` accessor belongs to this script's project.
val kotlinJvmPluginId = libs.plugins.kotlin.jvm.get().pluginId
val kotlinSerializationPluginId = libs.plugins.kotlin.serialization.get().pluginId
val kotlinTestJunit5 = libs.kotlin.test.junit5

allprojects {
    group = "net.kigawa.kivra"
    version = "0.0.1"
}

subprojects {
    apply(plugin = kotlinJvmPluginId)
    apply(plugin = kotlinSerializationPluginId)

    configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    dependencies {
        "testImplementation"(kotlinTestJunit5)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // H2's NOW() follows the JVM zone; production sessions are UTC (see README).
        systemProperty("user.timezone", "UTC")
        jvmArgs("-Duser.timezone=UTC")
        // The H2 suite of kivra-app shares one test JVM and reached the Gradle default (512m) in CI (OutOfMemoryError in
        // the test executor). The default is too tight for the whole suite, so give each test JVM headroom.
        maxHeapSize = "1g"
    }
}
