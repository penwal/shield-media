plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.logan.shieldmedia.player"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    lint {
        // This module deliberately builds on Media3's @UnstableApi surface (renderers, audio sink, analytics).
        disable += "UnsafeOptInUsageError"
    }
}

dependencies {
    implementation(project(":core:designsystem"))

    val bom = platform(libs.androidx.compose.bom)
    implementation(bom)
    api(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)
    implementation(libs.jellyfin.media3.ffmpeg)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
