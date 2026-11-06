plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ekoehler.expressivecutout"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ekoehler.expressivecutout"
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = "0.4.0"
    }

    // Same key on every build: when the workflow hands over a key file (DEBUG_KEYSTORE_PATH), the debug
    // APK is signed with exactly that file, so each build installs as an update over the previous one.
    signingConfigs {
        val debugKeystorePath = System.getenv("DEBUG_KEYSTORE_PATH")
        if (!debugKeystorePath.isNullOrBlank()) {
            check(file(debugKeystorePath).exists()) { "DEBUG_KEYSTORE_PATH apunta a un archivo que no existe: $debugKeystorePath" }
            getByName("debug") {
                storeFile = file(debugKeystorePath)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.savedstate)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.lottie.compose)
    implementation(libs.haze)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)

    testImplementation(libs.junit)
}
