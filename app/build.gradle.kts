import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.plugin.compose)
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization)
    alias(libs.plugins.ktlint)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

android {
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/work/_temp/keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
        }
    }
    namespace = "nz.eloque.vidaa_tiles"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    buildFeatures {
        compose = true
    }

    defaultConfig {
        applicationId = "nz.eloque.vidaa_tiles"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs["release"]
        }
        debug {
            applicationIdSuffix = ".dev"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/** Downloads the TV client certificate bundle from Sidee so it is never committed here. */
abstract class FetchClientBundle : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val bytes = URI(url.get()).toURL().readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(digest == sha256.get()) { "Client bundle checksum mismatch: $digest" }
        outputDir
            .file("tv-client.bundle")
            .get()
            .asFile
            .writeBytes(bytes)
    }
}

val fetchClientBundle =
    tasks.register<FetchClientBundle>("fetchClientBundle") {
        url.set("https://raw.githubusercontent.com/mamsxiii/Sidee/1c8e95bf25c9d1503fc7af5f3e6f77792ae5942b/core/tv-client.bundle")
        sha256.set("955e90447fd7da7680ddcbbf1cc72807f7eaa065f14ff59d057d76811dad6d5c")
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(fetchClientBundle, FetchClientBundle::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.paho.mqtt)

    testImplementation(libs.junit)
}
