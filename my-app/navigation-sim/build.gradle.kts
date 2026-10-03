// Desktop simulator for the navigation core.
//
// Compiles navigation-core FROM SOURCE, exactly as the Expo Android module does (a srcDir, not a
// published artifact), so a simulation always runs the core as it is on disk right now. Like the
// core itself it needs no Android SDK, no ARCore and no device.
//
//   ./gradlew test                                  scenario suite (the assertions)
//   ./gradlew run --args="--all --seeds 10"         batch report + PNG snapshots of failures
//   ./gradlew run --args="--live 8765 --scenario find-room-314"   watch live in Foxglove

plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories {
    mavenCentral()
}

kotlin {
    sourceSets {
        named("main") {
            kotlin.srcDirs("src/main/kotlin", "../navigation-core/src/main/kotlin")
        }
        named("test") { kotlin.srcDirs("src/test/kotlin") }
    }
}

dependencies {
    // Live streaming to Foxglove (MIT). Small, pure Java, and also runs on Android.
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("org.slf4j:slf4j-nop:2.0.16")
    // Claude, for the benchmark's LLM adviser (see advice/LlmClient.kt). Simulator only.
    implementation("com.anthropic:anthropic-java:2.34.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.navassist.sim.MainKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

// SIM_XMX=3g caps the simulator's heap, for a machine short of memory.
tasks.named<JavaExec>("run") {
    System.getenv("SIM_XMX")?.let { maxHeapSize = it }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    // Snapshots of failing scenarios land here, next to the build output.
    systemProperty("sim.reportDir", layout.buildDirectory.dir("sim-reports").get().asFile.absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
