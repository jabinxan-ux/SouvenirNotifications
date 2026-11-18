plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

layout.buildDirectory.set(layout.projectDirectory.dir("build-souvenir-0.4.17"))

dependencies {
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    // 1.19.1 needs compileSdk 37 and AGP 9.1; keep 1.17.0 for the Android 16/API 36 target toolchain.
    implementation("androidx.core:core-ktx:1.17.0")
}

android {
    namespace = "fr.xan.souvenirnotifications"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "fr.xan.souvenirnotifications"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "0.4.17"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
