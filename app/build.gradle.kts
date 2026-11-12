plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.limpio.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.limpio.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "5.0"
    }

    // En GitHub Actions se usa una llave fija (guardada en la caché privada del repo),
    // así cada actualización se instala encima de la anterior.
    val ciKeystore = System.getenv("LIMPIO_KEYSTORE")
    signingConfigs {
        if (ciKeystore != null) {
            create("limpio") {
                storeFile = file(ciKeystore)
                storePassword = "limpio-ci"
                keyAlias = "limpio"
                keyPassword = "limpio-ci"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName(if (ciKeystore != null) "limpio" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.coil-kt.coil3:coil-compose:3.1.0")
    implementation("io.coil-kt.coil3:coil-video:3.1.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    testImplementation("junit:junit:4.13.2")
}
