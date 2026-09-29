plugins {
    id("com.android.library") version "9.2.1"
}

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 37
    ndkVersion = providers.gradleProperty("nexaNdkVersion").orNull ?: "29.0.14206865"

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    compileOnly("androidx.media3:media3-exoplayer:1.11.1")
    compileOnly("androidx.annotation:annotation:1.9.1")
    compileOnly("org.checkerframework:checker-qual:3.43.0")
}
