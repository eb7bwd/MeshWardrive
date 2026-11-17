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
        versionCode = 10
        versionName = "0.6.0"
    }

    // Firma fija: en GitHub Actions la clave llega por variables de entorno (secretos del repositorio).
    // Sin ellas (por ejemplo al compilar en tu ordenador) se firma con la clave de pruebas.
    val keystorePath: String? = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "meshwardrive"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
