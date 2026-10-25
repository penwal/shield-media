plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.logan.shieldmedia.plex.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
}
