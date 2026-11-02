plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.glassweather"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.glassweather"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        create("release") {
            storeFile = file("glassweather.jks")
            storePassword = "glassweather123"
            keyAlias = "glass"
            keyPassword = "glassweather123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint { checkReleaseBuilds = false }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Health Connect: шаги и калории из Xiaomi «Здоровье» / Mi Fitness и других приложений
    implementation("androidx.health.connect:connect-client:1.1.0-alpha08")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
