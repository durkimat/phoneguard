plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.durkimat.familychat"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.durkimat.familychat"
        minSdk = 30
        targetSdk = 34
        // release-familychat.sh passes -PfcVersion=1.0.0; the code is major*10000 + minor*100 + patch, as for Phone Guard
        val fcVersion = (project.findProperty("fcVersion") as String?) ?: "0.0.1"
        // a release candidate "1.0.0-rc2" sorts below the release 1.0.0 (code 10000): its code is 10000 - 100 + 2
        val rc = Regex("-rc([0-9]+)$").find(fcVersion)?.groupValues?.get(1)?.toIntOrNull()
        val parts = fcVersion.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
        versionCode = maxOf(1, parts[0] * 10000 + parts[1] * 100 + parts[2] - (if (rc != null) 100 - rc else 0))
        versionName = fcVersion
        ndk { abiFilters += "arm64-v8a" } // the WhatsApp bridge is built for arm64 only
    }

    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":wa-core"))
    implementation(files("../../wabridge/out/wabridge.aar"))
    testImplementation("junit:junit:4.13.2")
}
