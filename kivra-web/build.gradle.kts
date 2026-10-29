// kivra-web: Wasm build and distribution for Compose Web UI.
// Depends on kivra-compose-ui for shared UI code.

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "kivra-web.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":kivra-compose-ui"))
                implementation(compose.ui)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.runtime)
                implementation(compose.components.resources)

                // Ktor Client for API calls
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
    }
}
