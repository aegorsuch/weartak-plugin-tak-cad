plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sdkAar = providers.gradleProperty("weartakSdkAar")
    .orElse(providers.environmentVariable("WEARTAK_PLUGIN_SDK_AAR"))
    .orNull ?: error("Set -PweartakSdkAar=<local WearTAK plugin SDK AAR>; vendor binaries are not stored here.")
require(file(sdkAar).isFile) { "WearTAK plugin SDK AAR does not exist: $sdkAar" }
val hostServicesAar = providers.gradleProperty("weartakHostServicesAar")
    .orElse(providers.environmentVariable("WEARTAK_HOST_SERVICES_SDK_AAR"))
    .orNull ?: error("Set -PweartakHostServicesAar=<WearTAK Host Services SDK 1.0.0 AAR>.")
require(file(hostServicesAar).isFile) { "WearTAK Host Services SDK AAR does not exist: $hostServicesAar" }

android {
    namespace = "com.weartak.plugin.takcad"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.weartak.plugin.takcad"
        minSdk = 30
        targetSdk = 34
        val baseVersionCode = 584
        val baseVersionName = "5.8.4"
        val pipeline = System.getenv("CI_PIPELINE_IID")
        if (pipeline != null) {
            val iid = pipeline.toIntOrNull()
                ?: throw GradleException("CI_PIPELINE_IID must be an integer.")
            if (iid !in 0..9999) {
                throw GradleException("CI_PIPELINE_IID $iid exceeds the versionCode pipeline range (0-9999).")
            }
            val sha = System.getenv("CI_COMMIT_SHORT_SHA") ?: "local"
            versionCode = baseVersionCode * 10000 + iid
            versionName = "$baseVersionName-p$iid+$sha"
        } else {
            versionCode = baseVersionCode
            versionName = baseVersionName
        }
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
    implementation(files(hostServicesAar))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}
