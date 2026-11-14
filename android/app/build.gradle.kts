import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "name.onetrue.supertuxrower"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "name.onetrue.supertuxrower"
        // The Ergatta tablet runs Android 9.
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // The Anthropic SDK's dependencies each carry these; Android can only keep one copy.
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST", "META-INF/*.md",
        )
        // Termux's native library starts shell processes; we only use its emulator and renderer.
        jniLibs.excludes += "**/libtermux.so"
    }

    testOptions {
        // Unit tests run on the JVM; Android framework calls return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    // Brainpower: the agent's brain.
    implementation("com.anthropic:anthropic-java:2.75.0")
    implementation("com.github.termux.termux-app:terminal-view:v0.118.3")

    testImplementation("junit:junit:4.13.2")
}
