// kivra-app: Ktor application, configuration, Flyway migrations, Exposed tables and repositories.
plugins {
    application
    alias(libs.plugins.ktor)
}

// Copy Compose Web Wasm distribution output to Resources/ui/compose/ so UiRoutes can serve it.
// This task runs before processResources, ensuring the UI assets are available on the classpath.
val copyComposeWebDist = tasks.register("copyComposeWebDist", Copy::class) {
    dependsOn(":kivra-web:wasmJsBrowserDistribution")
    val distDir = project(":kivra-web").layout.buildDirectory.dir("dist/wasmJs/productionExecutable")
    from(distDir)
    into(layout.buildDirectory.dir("resources/main/ui/compose"))
    // Only copy essential files: index.html, JS, wasm, and composeResources
    include("index.html")
    include("*.js")
    include("*.css")
    include("*.wasm")
    include("composeResources/**")
}

tasks.named("processResources") {
    dependsOn(copyComposeWebDist)
}
