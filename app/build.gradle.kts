import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------- which builds ship what --
//
// TWO KINDS OF BUILD, one codebase:
//
//   public    (the free GitHub APK)  — login offers M3U link + Xtream only.
//   customer  (-PwithPanel)          — the same app plus a "Rogue TV" tab that logs into the
//                                      panel in baked.properties, so a customer only types
//                                      their username/password.
//
// baked.properties is gitignored, so a clean clone can only produce the public build. The
// switch is opt-in ON PURPOSE: this machine always has that file, so a default-on panel
// would quietly put the portal inside every APK built here — including a public one.
//
//   gradlew assembleLegacyRelease                 → public   (2 login types)
//   gradlew assembleLegacyRelease -PwithPanel      → customer (3 login types)
//
val withPanel = providers.gradleProperty("withPanel").isPresent

// ---------------------------------------------------------------- version + package identity --
//
// Android only offers an update when versionCode goes UP, so the code cannot be a hand-typed
// constant that nobody remembers to bump: it is the number of commits, which is monotonic by
// construction. Both are overridable for a hotfix build off the same commit.
//
// The package name is overridable too, so a customer build installs next to your own:
//   gradlew assembleLegacyRelease -PwithPanel -PappId=com.rogueplayer.tv
val gitCommitCount: Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toInt()
}.getOrDefault(0)
val buildVersionCode: Int = providers.gradleProperty("versionCode").orNull?.toIntOrNull()
    ?: gitCommitCount.coerceAtLeast(1)
val buildVersionName: String = providers.gradleProperty("versionName").orNull
    ?: "1.0." + gitCommitCount
val appIdOverride: String? = providers.gradleProperty("appId").orNull
val bakedPanel = Properties().apply {
    val f = rootProject.file("baked.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun baked(key: String): String = if (withPanel) bakedPanel.getProperty(key).orEmpty() else ""

// Release signing comes from keystore.properties (also gitignored): storeFile, storePassword,
// keyAlias, keyPassword. Kept out of the repo on purpose — the key is the app's identity, and
// anyone who has it can ship an update your customers will accept. Absent file = unsigned
// release, which is what a clone gets and what CI would use with its own secrets.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.rogueplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rogueplayer"
        minSdk = 29
        targetSdk = 36
        versionCode = buildVersionCode
        versionName = buildVersionName
        vectorDrawables { useSupportLibrary = true }
        ndk { abiFilters += listOf("arm64-v8a") }
        buildConfigField("String", "BAKED_HOST", "\"" + baked("host") + "\"")
        buildConfigField("String", "BAKED_USER", "\"" + baked("user") + "\"")
        buildConfigField("String", "BAKED_PASS", "\"" + baked("pass") + "\"")
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
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
            applicationId = appIdOverride ?: "com.rogueplayer.basic"
            versionName = buildVersionName + "-basic"
        }
        create("locked") {
            dimension = "edition"
            applicationId = appIdOverride ?: "com.rogueplayer.locked"
            versionName = buildVersionName + "-locked"
        }
        // CarStream-style test edition: the car side runs on the LEGACY projection
        // auto-sdk (real hosted activity, media/projection categories — NOT the
        // modern Car App Library's NAVIGATION category). Head-to-head vs basic:
        // AA should boot to the launcher/Maps instead of auto-opening Rogue, and
        // back/exit should genuinely leave the app. Phone UI is shared src/main.
        create("legacy") {
            dimension = "edition"
            applicationId = appIdOverride ?: "com.rogueplayer.legacy"
            versionName = buildVersionName + "-legacy"
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
        buildConfig = true
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

    // LEGACY flavor only: the unofficial projection auto-sdk (CarStream's route).
    // Local AAR (JitPack com.github.martoreto:aauto-sdk:v4.5, cached); jetifier
    // rewrites its android.support.* references to androidx at build time.
    "legacyImplementation"(files("libs/aauto-sdk-v4.5.aar"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")   // poster / channel-logo loading
    implementation("org.jsoup:jsoup:1.18.3")

    testImplementation("junit:junit:4.13.2")
}
