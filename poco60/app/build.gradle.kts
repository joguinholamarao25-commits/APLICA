plugins {
    id("com.android.application")
}

android {
    namespace = "com.marlon.poco60"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.marlon.poco60"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-vendor803c"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
