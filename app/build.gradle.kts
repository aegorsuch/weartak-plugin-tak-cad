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
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(files(sdkAar))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}
