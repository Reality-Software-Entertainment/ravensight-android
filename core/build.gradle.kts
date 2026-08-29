plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "com.github.Reality-Software-Entertainment.ravensight-android"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test-junit"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "core"
        }
    }
}
