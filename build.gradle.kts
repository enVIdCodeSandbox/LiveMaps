import org.gradle.api.attributes.java.TargetJvmVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
    id("com.gradleup.shadow") version "8.3.5"
}

group = "dev.etran"
version = "1.0.3"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.bluecolored.de/releases")
}

dependencies {
    // Server API (Paper is required: Adventure/MiniMessage are used for messages).
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")

    // Oldest published versions of each API: everything we call exists unchanged in all newer versions.
    compileOnly("de.bluecolored:bluemap-api:2.7.3")
    compileOnly("xyz.jpenilla:squaremap-api:1.1.0")

    testImplementation("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// bluemap-api is published for Java 21 only. We just read its class files, our own bytecode stays Java 17.
configurations.matching { it.name == "compileClasspath" || it.name == "testCompileClasspath" }.configureEach {
    attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Paper 1.20.4 runs on Java 17+, so emit Java 17 bytecode.
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Djava.awt.headless=true")
    systemProperty("livemaps.dump", System.getProperty("livemaps.dump") ?: "false")
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.shadowJar {
    archiveClassifier.set("")
    // Shade only the Kotlin runtime, relocated so it can't clash with other plugins.
    relocate("kotlin", "dev.etran.livemaps.libs.kotlin")
    relocate("org.jetbrains", "dev.etran.livemaps.libs.jetbrains")
    minimize()
}

tasks.build { dependsOn(tasks.shadowJar) }
