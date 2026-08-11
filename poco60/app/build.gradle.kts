plugins {
    id("com.android.application")
}

android {
    namespace = "com.marlon.poco60"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.marlon.poco60.physical2"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "2.0-physical2-hfps-only"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
