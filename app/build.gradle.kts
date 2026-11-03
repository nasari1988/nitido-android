plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nitido.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nitido.app"
        minSdk = 29
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("personal") {
            storeFile = file("nitido.keystore")
            storePassword = "nitido123"
            keyAlias = "nitido"
            keyPassword = "nitido123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
        debug {
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    androidResources { noCompress += listOf("tflite", "mnn") }
    packaging { jniLibs { useLegacyPackaging = true } }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.16.1")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    implementation("com.google.android.gms:play-services-base:18.5.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}

// The Stable Diffusion engine and VAE encoder are built from source (ci/native.sh)
// whenever they are missing, so every build of the APK includes them.
val nativeLib = file("src/main/jniLibs/arm64-v8a/libsdjni.so")
val vaeEncoder = file("src/main/assets/vae_encoder.mnn")
val buildNative by tasks.registering(Exec::class) {
    onlyIf { !nativeLib.exists() || !vaeEncoder.exists() }
    workingDir = rootDir
    commandLine("bash", "ci/native.sh")
}
val checkNative by tasks.registering {
    dependsOn(buildNative)
    doLast {
        if (!nativeLib.exists()) throw GradleException("Falta libsdjni.so")
        if (!vaeEncoder.exists()) throw GradleException("Falta vae_encoder.mnn")
    }
}
tasks.named("preBuild") { dependsOn(checkNative) }
