plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.durkimat.phoneguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.durkimat.phoneguard"
        minSdk = 30
        targetSdk = 34
        // the release script passes -PpgVersion=0.2.0; the code is that number as major*10000 + minor*100 + patch, so every release is higher
        val pgVersion = (project.findProperty("pgVersion") as String?) ?: "0.1.9" // development builds sit between the test phone's version and the next release
        val parts = pgVersion.split(".").map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
        versionCode = maxOf(1, parts[0] * 10000 + parts[1] * 100 + parts[2])
        versionName = pgVersion
        ndk { abiFilters += "arm64-v8a" } // the WhatsApp bridge is built for arm64 only (the J36 is arm64)
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // the themed camera (Apache-2.0): still and video capture plus live frames, no ready-made camera screen
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-video:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4") // the plain colour viewfinder used while recording video
    // internet radio: streams (MP3, AAC, HLS) and the "now playing" text they carry (Apache-2.0)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    // the WhatsApp bridge (Go, built by ../../wabridge/build.sh)
    implementation(files("../../wabridge/out/wabridge.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
