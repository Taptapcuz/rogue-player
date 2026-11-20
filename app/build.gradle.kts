plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rogueplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rogueplayer"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        vectorDrawables { useSupportLibrary = true }
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Two editions of the same app, both branded Rogue Player, installable side
    // by side on one device via distinct applicationIds. Built whole first, then
    // the feature split gates: Basic (free, core playback + EPG) vs Locked
    // (premium, advanced gated features). Per-edition overrides go in
    // app/src/<flavor>/...; shared code stays in app/src/main.
    flavorDimensions += "edition"
    productFlavors {
        create("basic") {
            dimension = "edition"
            applicationId = "com.rogueplayer.basic"
            versionName = "1.0-basic"
        }
        create("locked") {
            dimension = "edition"
            applicationId = "com.rogueplayer.locked"
            versionName = "1.0-locked"
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
        compose = false
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-service:2.9.1")

    // Media playback — ExoPlayer (default engine)
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // Optional second engine: libVLC plays containers/codecs ExoPlayer rejects on some
    // providers (HEVC/AC3, odd HLS, RTSP). Natives are per-ABI and large, so the build is
    // filtered to arm64 (every device this ships to) — see ndk below.
    implementation("org.videolan.android:libvlc-all:3.7.7")

    // Classic media session: lets the car YouTube screen hold an ACTIVE media session
    // so Android Auto routes its audio as media. AA's focus manager reads
    // MediaSessionManager.getActiveSessions(); Media3's "active" state is
    // notification-driven and unreliable from a car session with no foreground
    // service, so this explicit isActive() is the dependable route.
    implementation("androidx.media:media:1.7.0")

    // Android Auto / Android for Cars (Car App Library)
    implementation("androidx.car.app:app:1.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")   // poster / channel-logo loading
    implementation("org.jsoup:jsoup:1.18.3")

    testImplementation("junit:junit:4.13.2")
}
