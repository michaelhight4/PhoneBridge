// Plain Kotlin/JVM module (no android.* imports, no Android SDK needed): command dispatch,
// permission checks, argument parsing, UI-tree node ids and the device seam that the Android app
// implements. JVM unit tests run on any machine with a JDK.
plugins { kotlin("jvm") }

repositories { mavenCentral() }

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
