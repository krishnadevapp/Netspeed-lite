plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.krishna.netspeedlite"

    // ⭐ API 36 is required by your current library versions (Core-ktx 1.17+)
    compileSdk = 36

    defaultConfig {
        applicationId = "com.krishna.netspeedlite"
        minSdk = 26

        // Target API 35 is stable for 2025 Play Store requirements
        targetSdk = 35

        // ⭐ UPDATED: Version Code must be higher for Play Store update
        versionCode = 26

        // ⭐ UPDATED: Version Name to reflect new release
        versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // ⭐ Enables R8 to shrink your code (Essential for a "Lite" app)
            isMinifyEnabled = true

            // ⭐ Removes unused images and resources to save space
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }



    buildFeatures {
        viewBinding = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // These libraries require compileSdk 36
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // In-App Review API
    implementation(libs.play.review.ktx)

    // WorkManager for background tasks
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}