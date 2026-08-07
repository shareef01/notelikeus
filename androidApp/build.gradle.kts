plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.aus.notelikeus"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aus.notelikeus"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.0"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.koin.android)
    implementation(libs.koin.core)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.viewmodel)
    
    implementation(libs.androidx.appfunctions)
    implementation(libs.androidx.appfunctions.service)
    ksp(libs.androidx.appfunctions.compiler)
    
    // UI dependencies for MainActivity
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.biometric)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation(libs.androidx.work.runtime.ktx)
    
    // Explicitly add Compose Material3 and WindowSizeClass for Android
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.windowSizeClass)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

ksp {
    arg("appfunctions:aggregateAppFunctions", "true")
}
