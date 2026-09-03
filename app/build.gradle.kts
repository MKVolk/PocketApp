plugins {
    id("com.android.application")
    //id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mkvolk.staticserver"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mkvolk.staticserver"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.1"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")

    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("com.google.zxing:core:3.5.3")
}


/*
plugins {
    alias(libs.plugins.android.application)

}

android {
    namespace = "com.mkvolk.museumv2"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mkvolk.museumv2"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
*/