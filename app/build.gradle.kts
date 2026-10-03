plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ovh.adan.pttbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "ovh.adan.pttbridge"
        minSdk = 21          // MediaSession
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"
    }

    // Release signing: the keystore and its passwords live outside the repo
    // (~/pttbridge-release.jks and ~/.gradle/gradle.properties). Without them
    // the release build falls back to the debug key, so anyone can build it.
    val ks = file(System.getProperty("user.home") + "/pttbridge-release.jks")
    signingConfigs {
        if (ks.exists()) create("release") {
            storeFile = ks
            storePassword = project.findProperty("pttbridgeStorePw") as String? ?: ""
            keyAlias = "pttbridge"
            keyPassword = project.findProperty("pttbridgeKeyPw") as String? ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = if (ks.exists()) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies { }
