// kivra-compose-ui: Compose Multiplatform UI code (platform-independent).
// Targets: jvm (for desktop/android/ios later) + wasmJs (for web now).

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm()
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "kivra-compose-ui.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(compose.ui)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.runtime)
                implementation(compose.components.resources)
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
                implementation(libs.ktor.client.core.wasm)
                implementation(libs.ktor.client.js)
                implementation(libs.ktor.client.content.negotiation.wasm)
                implementation(libs.ktor.serialization.kotlinx.json.wasm)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        jvmMain {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}
