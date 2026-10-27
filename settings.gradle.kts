pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        // The gateway's own Kotlin SDK (athena-gateway-client), built by JitPack from its tags.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.Gekkotron.Athena-HttpMqttGateway") }
        }
    }
}

rootProject.name = "Oikos"

include(":shared")
include(":core:model")
include(":core:crypto")
include(":core:transport")
include(":core:state")
include(":core:source")
include(":core:voice")
include(":core:data")
include(":core:alerts")
include(":core:history")
include(":core:flows")
include(":core:push")
include(":app")
include(":core:demo")
include(":core:designsystem")
include(":feature:home")
include(":feature:onboarding")
include(":feature:energy")
include(":feature:history")
include(":feature:security")
include(":feature:settings")
include(":feature:alerts")
include(":feature:widget")
include(":feature:voice")
