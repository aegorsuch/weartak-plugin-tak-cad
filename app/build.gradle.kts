plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sdkAar = providers.gradleProperty("weartakSdkAar")
    .orElse(providers.environmentVariable("WEARTAK_PLUGIN_SDK_AAR"))
    .orNull ?: error("Set -PweartakSdkAar=<local WearTAK plugin SDK AAR>; vendor binaries are not stored here.")
require(file(sdkAar).isFile) { "WearTAK plugin SDK AAR does not exist: $sdkAar" }

android {
    namespace = "com.weartak.plugin.takcad"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.weartak.plugin.takcad"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Robolectric fetches its android-all jars from inside the test JVM, bypassing
            // Gradle's repositories, and Maven Central rate-limits the CI runners (HTTP 429).
            if (gradle.extra.has("takArtifactoryUrl")) {
                test.systemProperty("robolectric.dependency.repo.id", "takArtifactory")
                test.systemProperty("robolectric.dependency.repo.url", gradle.extra["takArtifactoryUrl"]!!)
                test.systemProperty("robolectric.dependency.repo.username", gradle.extra["takArtifactoryUser"]!!)
                test.systemProperty("robolectric.dependency.repo.password", gradle.extra["takArtifactoryPassword"]!!)
            }
        }
    }
}

apply(from = rootProject.file("gradle/weartak-sideload-production.gradle"))

dependencies {
    implementation(files(sdkAar))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}
