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
    "kivra-exkes",
    "kivra-github",
    "kivra-model-gateway",
    "kivra-policy",
    "kivra-app",
    "kivra-observability",
    "kivra-ui-api",
    "kivra-developer",
    "kivra-agent",
)
