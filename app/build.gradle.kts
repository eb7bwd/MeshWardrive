import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.meshwardrive.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.meshwardrive.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.5.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Firmado con la clave de depuración para poder instalarlo sin más.
            // Si algún día lo publicas, crea tu propia keystore (también gratis).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.viewpager:viewpager:1.0.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Mapas OpenStreetMap, licencia Apache 2.0, sin claves de API
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // Mapas vectoriales offline (.map de Mapsforge, LGPL v3); trae mapsforge 0.21.0
    implementation("org.osmdroid:osmdroid-mapsforge:6.1.20")
}
