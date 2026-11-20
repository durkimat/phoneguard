plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.durkimat.wacore"
    compileSdk = 34
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // The Go bridge (built by ../../wabridge/build.sh) is compiled against here; each APP module packages it with implementation(files(...)),
    // because a local AAR cannot be carried inside a library module's own AAR.
    compileOnly(files("../../wabridge/out/wabridge.aar"))
    testImplementation("junit:junit:4.13.2")
}
