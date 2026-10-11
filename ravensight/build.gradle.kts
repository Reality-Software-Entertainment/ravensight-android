plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}

group = "com.github.Reality-Software-Entertainment.ravensight-android"
version = "0.2.0"

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

    testOptions {
        unitTests.isIncludeAndroidResources = true
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
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
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
