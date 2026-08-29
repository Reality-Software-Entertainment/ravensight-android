plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}

group = "com.github.Reality-Software-Entertainment.ravensight-android"
version = "0.1.0"

android {
    namespace = "com.realityse.ravensight"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core"))
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "ravensight"
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
