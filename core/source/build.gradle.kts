plugins {
    id("oikos.kmp.jvm.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:flows"))
            api(project(":core:state"))
            api(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            api(project(":core:transport"))
            api(project(":core:voice"))
        }
        jvmTest.dependencies {
            implementation(project.dependencies.testFixtures(project(":core:transport")))
        }
    }
}
