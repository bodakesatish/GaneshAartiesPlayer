plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.gms.google.services)
}

android {
    namespace = "com.bodakesatish.ganeshaarties"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bodakesatish.ganeshaarties"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Customize APK name for release builds
            applicationVariants.all {
                val variant = this
                if (variant.buildType.name == "release") {
                    variant.outputs.all {
                        val output = this
                        if (output is com.android.build.gradle.internal.api.ApkVariantOutputImpl) {
                            // Example: appName-versionName-versionCode-release.apk
                            // You can customize this pattern as you like.
                            val appName = "GaneshAartiesPlayer" // Or use project.name, module name, etc.
                            output.outputFileName =
                                "${appName}-v${variant.versionName}-release.apk"
                        }
                    }
                }
            }

        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)

    implementation(libs.androidx.media)
    implementation(libs.firebase.firestore)

    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}