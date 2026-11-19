plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rogueplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mytv.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Two installable versions of the same app, side by side on one device.
    //  - "clone" keeps the personal IMPlayer-style build (com.mytv.app).
    //  - "clean" is the publish-safe redesign (own app id + label) built part by part.
    // A different applicationId lets both live on one phone at the same time.
    // Visual differences for "clean" go in app/src/clean/... overrides; shared
    // code stays in app/src/main.
    flavorDimensions += "edition"
    productFlavors {
        create("clone") {
            dimension = "edition"
            applicationId = "com.mytv.app"
            versionName = "1.0-clone"
        }
        create("clean") {
            dimension = "edition"
            applicationId = "com.mytv.app.clean"
            versionName = "1.0-clean"
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

    // Media playback
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

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
