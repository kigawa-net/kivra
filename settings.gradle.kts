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
    "kivra-fusha-task",
    "kivra-fusha-poller",
    "kivra-core",
    "kivra-app",
)
