plugins {
    kotlin("jvm") version "2.3.21"
}

group = "me.centralhardware"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("dev.inmo:tgbotapi:33.1.0")
}

kotlin {
    jvmToolchain(25)
}
