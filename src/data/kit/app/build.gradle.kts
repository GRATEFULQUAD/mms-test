plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mmstest"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mmstest"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Proven (Apache-2.0) MMS PDU builder: SendReq / PduComposer / PduBody / PduPart.
    implementation("com.klinkerapps:android-smsmms:5.2.6")

    // Reliable video transcode to shrink clips under the MMS size ceiling.
    implementation("androidx.media3:media3-common:1.3.1")
    implementation("androidx.media3:media3-effect:1.3.1")
    implementation("androidx.media3:media3-muxer:1.3.1")
    implementation("androidx.media3:media3-transformer:1.3.1")
}