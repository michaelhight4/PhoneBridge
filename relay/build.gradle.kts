plugins {
    kotlin("jvm")
    application
}

repositories { mavenCentral() }

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    // Envelope/identity types, X25519, HMAC and the shared JSON config all come from :core.
    // :core exposes kotlinx-serialization-json and Bouncy Castle as api() dependencies.
    implementation(project(":core"))
    testImplementation(kotlin("test"))
}
tasks.test { useJUnitPlatform() }

application {
    mainClass.set("dev.phonebridge.relay.MainKt")
    applicationName = "phonebridge-relay"
}

// Standalone single-file jar for a VPS: `gradlew.bat :relay:shadowJar`
// (hand-rolled fat jar so no extra Gradle plugin is required).
tasks.register<Jar>("shadowJar") {
    group = "build"
    description = "Builds a standalone relay jar with all runtime dependencies."
    archiveBaseName.set("phonebridge-relay")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "dev.phonebridge.relay.MainKt" }
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}
