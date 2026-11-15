plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.livespace.sensor"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.livespace.sensor"
        minSdk = 23
        targetSdk = 37
        versionCode = 4
        versionName = "0.4.0"
        ndk { abiFilters += "armeabi-v7a" }
    }
    androidResources { noCompress += "tflite" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies { implementation("com.squareup.okhttp3:okhttp:4.12.0"); testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0"); testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0"); testImplementation("org.json:json:20240303"); implementation("org.tensorflow:tensorflow-lite:2.8.0"); testImplementation("junit:junit:4.13.2") }
