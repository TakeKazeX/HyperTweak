import java.io.FileInputStream
import java.util.Properties

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
        val commitCount = providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
        }.standardOutput.asText.map { it.trim().toIntOrNull() ?: 1 }.getOrElse(1)

        versionCode = commitCount

        val isStableRelease = project.hasProperty("stable") ||
                (System.getenv("BUILD_CHANNEL") == "stable") ||
                (System.getenv("GITHUB_REF_NAME")?.matches(Regex("^v[0-9.]+$")) == true)
        val isCI = System.getenv("GITHUB_ACTIONS") == "true"

        val baseVersion = "1.5.0"
        versionName = when {
            isStableRelease -> baseVersion
            isCI -> "$baseVersion-dev"
            else -> "$baseVersion-beta"
        }

        buildConfigField("String", "GIT_COMMIT_COUNT", "\"$commitCount\"")
        buildConfigField("boolean", "IS_BETA", (!isStableRelease).toString())

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            val keystoreFile = file("release.keystore")
            if (keystoreFile.exists()) {
                val properties = Properties()
                val localPropertiesFile = rootProject.file("local.properties")
                if (localPropertiesFile.exists()) {
                    FileInputStream(localPropertiesFile).use { stream ->
                        properties.load(stream)
                    }
                }

                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?: properties.getProperty("keystore.password")
                    ?: ""
                keyAlias = System.getenv("KEY_ALIAS")
                    ?: properties.getProperty("key.alias")
                    ?: ""
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?: properties.getProperty("key.password")
                    ?: ""
            }
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
            val keystoreFile = file("release.keystore")
            if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    bundle {
        language {
            enableSplit = false
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val mainOutput = output as? com.android.build.api.variant.impl.VariantOutputImpl
            mainOutput?.outputFileName?.set("HyperTweak-v${variant.outputs.first().versionName.get()}-${variant.name}.apk")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    
    // Compose Runtime & UI
    implementation("androidx.compose.ui:ui:1.11.3")
    implementation("androidx.compose.ui:ui-graphics:1.11.3")
    implementation("androidx.compose.ui:ui-tooling-preview:1.11.3")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    // Miuix UI & Preferences
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.2")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.2")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.2")
    implementation("top.yukonga.miuix.kmp:miuix-blur:0.9.2")
    implementation("top.yukonga.miuix.kmp:miuix-navigation3-ui:0.9.2")
    implementation("androidx.navigation3:navigation3-runtime:1.1.3")
    implementation("androidx.navigationevent:navigationevent:1.1.2")

    // libxposed
    //noinspection NewerVersionAvailable
    compileOnly("io.github.libxposed:api:101.0.1")
    //noinspection NewerVersionAvailable
    implementation("io.github.libxposed:service:101.0.0")

    // EzHookTool
    implementation("io.github.lingqiqi5211.ezhooktool:core:1.1.0-rc04")
    implementation("io.github.lingqiqi5211.ezhooktool:hook-xposed-101:1.1.0-rc01")

    // DexKit
    implementation("org.luckypray:dexkit:2.2.0")
}

configurations.all {
    exclude(group = "androidx.navigationevent", module = "navigationevent-compose")
    exclude(group = "androidx.navigationevent", module = "navigationevent-compose-android")
}
