import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// End-to-end tests that drive the real launcher on a device or emulator with UI Automator.
// This is a separate, self-instrumenting test module so the tests run in their own process
// and can force-stop the launcher to check persistence. Run with:
//   ./gradlew :e2e:connectedDebugAndroidTest
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ni.launcher.e2e"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Test the dev flavor so the tests never touch the user's real launcher data.
        missingDimensionStrategy("edition", "dev")
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

androidComponents {
    beforeVariants { variant -> variant.enable = variant.buildType == "debug" }
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
}
