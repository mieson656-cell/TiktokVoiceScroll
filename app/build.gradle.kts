plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ru.voicescroll"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.voicescroll"
        minSdk = 29
        targetSdk = 35
        val ciBuild = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = ciBuild
        versionName = "1.0.$ciBuild"
        ndk { abiFilters += "arm64-v8a" }
    }
    val keystorePath = System.getenv("KEYSTORE_PATH")
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
    val keyAliasValue = System.getenv("KEY_ALIAS")
    val keyPasswordValue = System.getenv("KEY_PASSWORD")
    if (!keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank()
        && !keyAliasValue.isNullOrBlank() && !keyPasswordValue.isNullOrBlank()) {
        signingConfigs {
            create("persistentRelease") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
        buildTypes {
            getByName("debug") {
                signingConfig = signingConfigs.getByName("persistentRelease")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47")
}
