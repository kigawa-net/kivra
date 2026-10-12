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
    "kivra-scheduler",
    "kivra-policy",
    "kivra-app",
    "kivra-ui-api",
)
