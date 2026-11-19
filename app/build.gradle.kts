plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.karadeniz.altyazi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.karadeniz.altyazi"
        minSdk = 30
        targetSdk = 35
        versionCode = 12
        versionName = "0.11.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // Repodaki sabit anahtar: her derleme aynı imzayla çıkar, güncelleme üstüne kurulur.
        create("sabit") {
            storeFile = file("altyazi.keystore")
            storePassword = "altyazi123"
            keyAlias = "altyazi"
            keyPassword = "altyazi123"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sabit")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sabit")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    testImplementation("junit:junit:4.13.2")
}
