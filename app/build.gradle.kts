plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tap_to_translate.snap_translate.hymt2build"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.tap_to_translate.snap_translate"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "integration-helper"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DLLAMA_ROOT=${providers.gradleProperty("llamaRoot").orNull ?: error("Pass -PllamaRoot=<llama.cpp checkout>")}",
                    "-DVULKAN_AAR_NATIVE_DIR=${providers.gradleProperty("vulkanAarNativeDir").orNull ?: error("Pass -PvulkanAarNativeDir=<AAR jni/arm64-v8a>")}",
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            pickFirsts += setOf("lib/arm64-v8a/libc++_shared.so")
        }
    }
}

dependencies {
    implementation(files("libs/llama-android-vulkan.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.1")
    implementation("com.quickbirdstudios:opencv:4.5.3.0")
    testImplementation("junit:junit:4.13.2")
}
