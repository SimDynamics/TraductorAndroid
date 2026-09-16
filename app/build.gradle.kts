plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.traductorandroid"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.traductorandroid"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
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
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    // Vosk: reconocimiento de voz sin conexión.
    implementation("com.alphacephei:vosk-android:0.3.75@aar")

    // JNA permite que la parte Java/Kotlin de Vosk se comunique
    // con sus bibliotecas nativas.
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    // ML Kit: traducción de texto en el dispositivo.
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
}