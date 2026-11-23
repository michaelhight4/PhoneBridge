plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

repositories { mavenCentral() }

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    // Protocol types, pairing, Noise sessions, control arbiter and chunking.
    implementation(project(":core"))
    // Embedded relay ("the heart") and the reconnecting relay client.
    implementation(project(":relay"))
    // Pairing QR bitmap (core only: no Android or Swing helpers). Apache-2.0.
    implementation("com.google.zxing:core:3.5.3")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Unit tests never capture the screen or move the real mouse: every device touch goes through a
    // seam (ScreenSource / InputSink / ProcessLauncher) with a fake in the test source set.
    systemProperty("java.awt.headless", "true")
}

application {
    mainClass.set("dev.phonebridge.desktop.MainKt")
    applicationName = "phonebridge-desktop"
}
