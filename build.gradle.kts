import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import groovy.util.Node
import groovy.util.NodeList
import org.gradle.api.tasks.bundling.Jar
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import java.time.ZoneOffset


plugins {
    kotlin("jvm") version "1.3.20"
    id("org.jlleitschuh.gradle.ktlint") version "6.3.1"
}

group = "io.hexlabs.sckema"
version = "0.1-SNAPSHOT"

repositories {
    mavenCentral()
    jcenter()
}

dependencies {
    compile("com.fasterxml.jackson.core:jackson-databind:2.9.0")
    compile("com.fasterxml.jackson.module:jackson-module-kotlin:2.9.0")
    compile("com.squareup:kotlinpoet:1.0.1")
    testCompile("org.jetbrains.kotlin:kotlin-test:1.3.20")
    testCompile("junit:junit:4.12")
}

java.sourceSets["main"].withConvention(KotlinSourceSet::class) {
    kotlin.srcDirs("src/main/myKotlin", "out/production/generated-sources")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

configure<KtlintExtension> {
    verbose.set(true)
    outputToConsole.set(true)
    coloredOutput.set(true)
    reporters.set(setOf(ReporterType.CHECKSTYLE, ReporterType.JSON))
}
