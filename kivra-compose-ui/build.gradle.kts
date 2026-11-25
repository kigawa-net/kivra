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
        // 実行可能バイナリ(productionExecutable)は作らない。compose-ui は kivra-web から klib として使われるだけで、
        // この .js は配信にも Docker にも使われない。作ると build のたびに production の wasm コンパイルと
        // binaryen の最適化が余計に走り、同じ Kotlin daemon のヒープを圧迫する(test-h2 の OOM)。
        // テスト用の wasm 実行ファイル(wasmJsTest)は binaries.executable() と無関係に作られる。
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
                // Ktor Client core (multiplatform)
                implementation(libs.ktor.client.core.wasm)
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
        val wasmJsMain by getting {
            dependencies {
                // Ktor Client engine for wasmJs
                implementation(libs.ktor.client.js)
            }
        }
    }
}
