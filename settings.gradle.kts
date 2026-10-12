pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kivra"

include(
    "kivra-memory",
    "kivra-core",
    "kivra-exkes",
    "kivra-app",
    "kivra-ui-api",
)
