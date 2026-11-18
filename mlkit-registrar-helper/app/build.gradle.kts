plugins {
    id("com.android.application")
}

android {
    namespace = "com.tap_to_translate.mlkitregistrarhelper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tap_to_translate.mlkitregistrarhelper"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "mlkit-registrar-helper"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }
}

dependencies {
    // Keep both SDKs on ML Kit Common 18.11.0, matching APK 219's
    // text-recognition runtime and the translation manager integration.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
}
