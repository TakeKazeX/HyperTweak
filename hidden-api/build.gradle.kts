import java.util.Properties

plugins {
    `java-library`
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val androidSdk = localProperties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    if (androidSdk != null) {
        compileOnly(files("$androidSdk/platforms/android-37.0/android.jar"))
    }
}
