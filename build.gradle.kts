import org.jetbrains.kotlin.gradle.tasks.KotlinCompile


plugins {
    kotlin("jvm") version "1.3.20"
}

group = "io.hexlabs.sckema"
version = "0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    compile(kotlin("stdlib-jdk8"))
    compile("com.fasterxml.jackson.core:jackson-databind:2.9.0")
    compile("com.fasterxml.jackson.module:jackson-module-kotlin:2.9.0")
    compile("com.squareup:kotlinpoet:1.0.1")
    testCompile("org.jetbrains.kotlin:kotlin-test:1.3.20")
    testCompile("junit:junit:4.12")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}
