plugins {
    id("com.android.application")
}

// Framework Views only, like Plume. The only libraries are for playback: Media3 (ExoPlayer) and an ffmpeg audio decoder.
android {
    namespace = "dev.streamtv"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.streamtv"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        // TVs are ARM; the x86 builds of ffmpeg's native code would only add size.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Debug key until the app has a name and its own release key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

// Media3 stays on the version Jellyfin's ffmpeg decoder is built against: its native glue targets one exact release.
val media3 = "1.9.0"

dependencies {
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3") // live and sports addons stream HLS
    implementation("androidx.media3:media3-ui:$media3")
    // Software audio for what TVs can't decode themselves (DTS, TrueHD), so those files still have sound.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")
    testImplementation("junit:junit:4.13.2")
}
