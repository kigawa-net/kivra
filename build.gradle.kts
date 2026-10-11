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
    }
}
