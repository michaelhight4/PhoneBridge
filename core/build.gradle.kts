plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

repositories { mavenCentral() }

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    // Wire format for every protocol message.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // Pure-JVM primitives that also exist on Android API 26+ (X25519, ChaCha20-Poly1305, SHA-256, HMAC).
    api("org.bouncycastle:bcprov-jdk18on:1.87")

    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
