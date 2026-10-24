# Dev-Sidebike release proguard rules (minify is disabled in beta; kept for future use).
-keep class dev.sidebike.core.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
