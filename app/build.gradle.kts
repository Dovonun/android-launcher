import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    kotlin("kapt")
}

val appVersionBase = providers.gradleProperty("APP_VERSION_BASE").get()
val ciVersionName = providers.gradleProperty("ciVersionName").orElse(appVersionBase)
val ciVersionCode = providers.gradleProperty("ciVersionCode").orElse("1").map(String::toInt)
val releaseApkName = providers.gradleProperty("releaseApkName").orElse("ni-launcher-release.apk")
val releaseStoreFilePath = System.getenv("RELEASE_STORE_FILE")
val useReleaseSigning = providers.gradleProperty("useReleaseSigning")
    .orElse(if (releaseStoreFilePath.isNullOrBlank()) "false" else "true")
    .map(String::toBoolean)

android {
    namespace = "com.ni.launcher"
    compileSdk = 36
    compileSdkExtension = 1

    signingConfigs {
        create("release") {
            if (!releaseStoreFilePath.isNullOrBlank()) {
                storeFile = file(releaseStoreFilePath)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.ni.launcher"
        minSdk = 30
        targetSdk = 36
        versionCode = ciVersionCode.get()
        versionName = ciVersionName.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a")
        }
    }

    flavorDimensions += "edition"
    productFlavors {
        create("public") {
            dimension = "edition"
        }
        create("dev") {
            dimension = "edition"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "${versionNameSuffix ?: ""}-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (useReleaseSigning.get()) {
                signingConfigs.getByName("release").takeIf {
                    !releaseStoreFilePath.isNullOrBlank()
                }
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors.set(true)
            freeCompilerArgs.addAll(
                "-Wextra",
                "-progressive"
            )
        }
    }
    buildFeatures {
        compose = true
    }
}

android.applicationVariants.configureEach {
    outputs.configureEach {
        val output = this as BaseVariantOutputImpl
        output.outputFileName = when {
            flavorName == "public" && buildType.name == "release" -> releaseApkName.get()
            flavorName == "dev" && buildType.name == "release" -> "ni-launcher-dev-release.apk"
            flavorName == "dev" && buildType.name == "debug" -> "ni-launcher-dev-debug.apk"
            flavorName == "public" && buildType.name == "debug" -> "ni-launcher-public-debug.apk"
            else -> output.outputFileName
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.text)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    kapt(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)


}
configurations.all {
    resolutionStrategy {
        force("org.jetbrains:annotations:23.0.0")
        exclude(group = "com.intellij", module = "annotations")
    }
}

tasks.register("assertReleaseSigningConfigured") {
    doLast {
        check(!releaseStoreFilePath.isNullOrBlank()) { "RELEASE_STORE_FILE is not set." }
        check(!System.getenv("RELEASE_STORE_PASSWORD").isNullOrBlank()) {
            "RELEASE_STORE_PASSWORD is not set."
        }
        check(!System.getenv("RELEASE_KEY_ALIAS").isNullOrBlank()) {
            "RELEASE_KEY_ALIAS is not set."
        }
        check(!System.getenv("RELEASE_KEY_PASSWORD").isNullOrBlank()) {
            "RELEASE_KEY_PASSWORD is not set."
        }
    }
}
