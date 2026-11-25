plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.traductor.voz"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.traductor.voz"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "4.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
