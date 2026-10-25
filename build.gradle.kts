plugins {
    // 9.5 removes AGP's deprecated Configuration.setVisible calls on Gradle 9.8+.
    id("com.android.application") version "9.5.0-rc01" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
