// Dev-Sidebike root build script.
// Backend core (:core) is a pure Kotlin/JVM library so it can be unit-tested on the JVM
// and reused by the Android app (:app) without shipping a Node.js runtime.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
