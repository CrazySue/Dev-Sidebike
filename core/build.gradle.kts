import java.time.Duration

// Dev-Sidebike backend core — pure Kotlin/JVM, Android-compatible APIs only
// (no java.net.http, no java.awt, no sun.* imports). Runs unmodified on the JVM for tests.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
    // Safety net: a hung test must not block the whole three-environment pipeline.
    timeout.set(Duration.ofMinutes(10))
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
