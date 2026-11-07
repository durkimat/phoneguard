plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.durkimat.phoneguard.lab"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.durkimat.phoneguard.lab"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1-lab"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
