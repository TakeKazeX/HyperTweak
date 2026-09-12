import java.io.FileInputStream
import java.time.Instant
import java.util.Properties

val baseVersion = providers.gradleProperty("hypertweak.version").get()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.takekazex.hypertweak"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.takekazex.hypertweak"
        // HyperOS OS3 (Android 16 / API 36) is the oldest supported platform; Android 15 was never
        // a supported target.
        minSdk = 36
        targetSdk = 37
        val explicitVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull()
        val commitCount = providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
        }.standardOutput.asText.map { it.trim().toIntOrNull() ?: 1 }.getOrElse(1)

        // The compare API needs the commit this build was made from: the versionCode identifies a
        // build for the update check, but only the SHA can tell GitHub which commits are missing
        // locally, which is what produces the per-distance changelog.
        val commitSha = providers.exec {
            commandLine("git", "rev-parse", "HEAD")
        }.standardOutput.asText.map { it.trim() }.getOrElse("")

        // Deliberately *not* a wall-clock instant. Configuration cache would freeze the first
        // build's value and every later build would report that stale time, which is a different
        // lie than showing nothing. A per-commit value is deterministic; CI or a reproducible
        // build can inject the real thing through BUILD_TIMESTAMP / SOURCE_DATE_EPOCH.
        val buildTimestamp = System.getenv("BUILD_TIMESTAMP")?.takeIf { it.isNotBlank() }
            ?: System.getenv("SOURCE_DATE_EPOCH")?.toLongOrNull()?.let { Instant.ofEpochSecond(it).toString() }
            ?: providers.exec {
                commandLine("git", "log", "-1", "--format=%cI")
            }.standardOutput.asText.map { it.trim() }.getOrElse("")

        versionCode = explicitVersionCode ?: commitCount

        val isStableRelease = project.hasProperty("stable") ||
                (System.getenv("BUILD_CHANNEL") == "stable") ||
                (System.getenv("GITHUB_REF_NAME")?.matches(Regex("^v[0-9.]+$")) == true)
        val isCI = System.getenv("GITHUB_ACTIONS") == "true"

        versionName = when {
            isStableRelease -> baseVersion
            isCI -> "$baseVersion-dev"
            else -> "$baseVersion-beta"
        }

        buildConfigField("String", "GIT_COMMIT_COUNT", "\"$commitCount\"")
        buildConfigField("String", "GIT_COMMIT_SHA", "\"$commitSha\"")
        buildConfigField("String", "BUILD_TIMESTAMP", "\"$buildTimestamp\"")
        buildConfigField("boolean", "IS_BETA", (!isStableRelease).toString())

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    testOptions {
        // JVM unit tests touch android.util.Log via DebugLog (e.g. the resolver's
        // degraded-resolution warnings); return defaults instead of failing on stubs.
        unitTests.isReturnDefaultValues = true
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
                logger.warn("Release keystore missing; producing debug-signed fallback APK")
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
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

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        // HyperTweak is an Xposed module: private framework/vendor APIs and resource names are
        // the integration surface of its hooks, and DexKit queries intentionally select the first
        // descriptor that passes their runtime shape validation. These diagnostics are not
        // actionable replacements for the verified host signatures used by the module.
        disable += setOf(
            "PrivateApi",
            "DiscouragedPrivateApi",
            "DiscouragedApi",
            // The DexKit detector emits this diagnostic before source-level suppressions are
            // applied. Keep this explicit for the reviewed fallback resolvers; changing any of
            // those first-match queries still requires a target-Apk review.
            "NonUniqueDexKitData"
        )
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val mainOutput = output as? com.android.build.api.variant.impl.VariantOutputImpl
            val suffix = if (variant.name == "release" && !file("release.keystore").exists()) "-debug-signed" else ""
            // The versionCode is part of the file name because every CI build shares the same
            // versionName ("1.8.0-dev"), which made two different builds indistinguishable both in
            // a local download folder and in a share link. Hyphens only: this name ends up inside a
            // releases/download/<tag>/<name> URL, where parentheses and "+" need escaping.
            val versionName = variant.outputs.first().versionName.get()
            val versionCode = variant.outputs.first().versionCode.get()
            mainOutput?.outputFileName?.set("HyperTweak-v$versionName-$versionCode-${variant.name}$suffix.apk")
        }
    }
}

tasks.register("printBaseVersion") {
    group = "help"
    description = "Prints the canonical application version used for release tags."
    val version = providers.gradleProperty("hypertweak.version")
    doLast {
        println(version.get())
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    
    // Compose Runtime & UI
    implementation("androidx.compose.ui:ui:1.12.1")
    implementation("androidx.compose.ui:ui-graphics:1.12.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.12.1")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    // Miuix UI & Preferences
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-blur:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-navigation3-ui:0.9.3")
    implementation("androidx.navigation3:navigation3-runtime:1.1.7")
    implementation("androidx.navigationevent:navigationevent:1.1.2")

    // libxposed
    compileOnly("io.github.libxposed:api:102.0.0")
    compileOnly(project(":hidden-api"))
    implementation("io.github.libxposed:service:102.0.0")

    // EzHookTool
    implementation("io.github.lingqiqi5211.ezhooktool:core:1.2.2")
    implementation("io.github.lingqiqi5211.ezhooktool:hook-xposed-102:1.2.2")

    // DexKit
    implementation("org.luckypray:dexkit:2.2.0")

    // Full SVG 1.1/Tiny parser and renderer for imported signal artwork.
    implementation("com.caverock:androidsvg-aar:1.4")
}

configurations.all {
    exclude(group = "androidx.navigationevent", module = "navigationevent-compose")
    exclude(group = "androidx.navigationevent", module = "navigationevent-compose-android")
}
