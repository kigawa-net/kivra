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
    "kivra-core",
    "kivra-exkes",
    "kivra-app",
)
