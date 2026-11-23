// The v1 end-to-end harness. It is a plain Kotlin/JVM module on purpose: it wires the real `:core`
// protocol/crypto, the real `:relay` server and client, the real plain-Kotlin command executor
// (`:android:executor-core`) and the real device-side session/approval controllers
// (`:android:ui-core`) together with a fake phone, so the whole v1 path is proven without an
// Android SDK or a real device.
plugins { kotlin("jvm") }

repositories { mavenCentral() }

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    // Protocol model, pairing, Noise XX, bot registry and the control arbiter.
    api(project(":core"))
    // The real relay server and reconnecting relay client.
    api(project(":relay"))
    // The executor that turns a request into a device call (no android.* imports).
    api(project(":android:executor-core"))
    // The device-side transport seam: SessionHandler + ApprovalController + BridgeState.
    api(project(":android:ui-core"))

    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

// `gradlew.bat :e2e:runFakePhone` starts a relay plus a fake phone and prints a pairing code, so an
// external bot (the MCP server/CLI once it lands, a script, a human with a phone app) can pair and
// drive it. The automated suite is `gradlew.bat :e2e:test`.
tasks.register<JavaExec>("runFakePhone") {
    group = "verification"
    description = "Start a relay and a fake phone; prints the pairing code and link."
    mainClass.set("dev.phonebridge.e2e.FakePhoneServerKt")
    classpath = sourceSets.main.get().runtimeClasspath
    standardInput = System.`in`
}
