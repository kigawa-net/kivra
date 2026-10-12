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
    "kivra-fusha-task",
    "kivra-fusha-poller",
    "kivra-core",
    "kivra-model-gateway",
    "kivra-app",
    "kivra-ui-api",
)
