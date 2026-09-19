// Standalone JVM build for the PLATFORM-INDEPENDENT navigation core.
//
// Why this exists as its own Gradle project rather than living inside the Expo Android module:
//
//  1. It is compiled here WITHOUT the Android or ARCore classpath, so a stray
//     `import com.google.ar.core.*` or `import android.*` in the core fails the build instead of
//     quietly creating a platform dependency.
//  2. The algorithmic tests run in seconds on a laptop with no Android SDK, emulator or device.
//  3. The same source tree is consumed by the Expo Android module through a srcDir (see
//     modules/navigation-native/android/build.gradle), and can later be lifted into
//     navigation-core/src/commonMain/kotlin for Kotlin Multiplatform + iOS without touching a
//     single algorithm.

plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
}

kotlin {
    // No jvmToolchain pin: this project only has to compile and run the algorithms on whatever
    // JDK the developer has. The Android module compiles the same sources from source (srcDir)
    // against its own toolchain, so nothing here constrains the app build.
    sourceSets {
        named("main") { kotlin.srcDirs("src/main/kotlin") }
        named("test") { kotlin.srcDirs("src/test/kotlin") }
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
