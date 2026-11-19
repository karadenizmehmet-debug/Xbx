plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.karadeniz.altyazi.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.karadeniz.altyazi.tv"
        minSdk = 26
        targetSdk = 33
        versionCode = 3
        versionName = "0.8.0"
    }

    signingConfigs {
        create("sabit") {
            storeFile = file("../app/altyazi.keystore")
            storePassword = "altyazi123"
            keyAlias = "altyazi"
            keyPassword = "altyazi123"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("sabit") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
