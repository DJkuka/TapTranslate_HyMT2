plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
}
android {
 namespace = "com.taptranslate.mlkitdiag"
 compileSdk = 35
 defaultConfig {
  applicationId = "com.taptranslate.mlkitdiagnostic"
  minSdk = 23
  targetSdk = 35
  versionCode = 1
  versionName = "1.1-vision-registrars"
 }
 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 // Keep Translate and Vision Text on modern Component-based registrars.
 implementation("com.google.mlkit:translate:17.0.3")
 implementation("com.google.mlkit:text-recognition:16.0.1")
}
