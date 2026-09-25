import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val tmdbKey: String = localProps.getProperty("TMDB_API_KEY") ?: System.getenv("TMDB_API_KEY") ?: ""
val keystoreFile: String? = localProps.getProperty("KEYSTORE_FILE")
val telegramApiId: String = localProps.getProperty("TELEGRAM_API_ID") ?: System.getenv("TELEGRAM_API_ID") ?: "0"
val telegramApiHash: String = localProps.getProperty("TELEGRAM_API_HASH") ?: System.getenv("TELEGRAM_API_HASH") ?: ""

android {
    namespace = "com.flox.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.flox.tv"
        minSdk = 28
        targetSdk = 34
        versionCode = 12
        versionName = "1.9.0"
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbKey\"")
        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId)
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = rootProject.file(keystoreFile)
                storePassword = localProps.getProperty("KEYSTORE_PASSWORD")
                keyAlias = localProps.getProperty("KEY_ALIAS")
                keyPassword = localProps.getProperty("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].kotlin.srcDir("src/main/kotlin")

    packaging {
        resources.excludes += setOf("META-INF/*.version", "kotlin/**", "META-INF/*.kotlin_module", "DebugProbesKt.bin")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.7.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.7.1")
    implementation("androidx.media3:media3-ui:1.7.1")
    // FFmpeg audio decoders for codecs the box lacks (E-AC3, DTS, TrueHD)
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.7.1-0.9.0")
    implementation("com.google.zxing:core:3.5.3")
}
