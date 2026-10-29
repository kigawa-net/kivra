pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
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
    "kivra-compose-ui",
    "kivra-web",
)

project(":kivra-compose-ui").projectDir = file("kivra-app/kivra-compose-ui")
