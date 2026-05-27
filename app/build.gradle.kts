plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.takekazex.hypertweak"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.takekazex.hypertweak"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            keyAlias = "hypertweak"
            keyPassword = "123456"
            storeFile = file("release.keystore")
            storePassword = "123456"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    
    // Compose Runtime & UI
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.ui:ui-graphics:1.7.6")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-core:1.7.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Miuix UI
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.1")

    // libxposed
    compileOnly("io.github.libxposed:api:101.0.1")
    implementation("io.github.libxposed:service:101.0.0")

    // KavaRef
    implementation("com.highcapable.kavaref:kavaref-core:1.0.2")
    implementation("com.highcapable.kavaref:kavaref-extension:1.0.2")

    // HiddenApiBypass
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    // DexKit
    implementation("org.luckypray:dexkit:2.2.0")
}
