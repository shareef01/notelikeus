// Imported rather than fully qualified: inside a Kotlin DSL script `java.` resolves to Gradle's
// `java` extension, not the JDK package.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.ksp)
}

/**
 * Release signing credentials from a gitignored `signing.properties` at the repo root
 * (see signing.properties.example). Absent, release builds stay unsigned, which is fine
 * locally and in CI but cannot be uploaded to Play.
 */
val signingProps: Properties? = rootProject.file("signing.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

android {
    namespace = "com.aus.notelikeus"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aus.notelikeus"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        // Same gradle.properties value composeApp compiles into AppConfig.versionName, so the
        // number stamped on the APK and the number the UI shows are the same number.
        versionName = providers.gradleProperty("notelikeus.versionName").get()
        // Required for the androidTest source set below; :composeApp sets its own.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    signingConfigs {
        if (signingProps != null) {
            create("release") {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            // Enabled with conservative keeps (proguard-rules.pro) and verified on an emulator:
            // launch, sign-in gate, editor, note persistence and a cold restart of the encrypted
            // database. Re-verify those paths after any rule change.
            isMinifyEnabled = true
            // Verified the same way R8 was: a release APK built, debug-signed, installed on the
            // emulator and driven through launch, the notes list, the filters sheet, the editor
            // and a cold restart of the encrypted database. Shrinking is the step that
            // historically breaks Compose resource lookups, so it does not go in on lint's word.
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
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

    // Only so NotelikeusDatabase's supertype resolves: the startup warm-up call in
    // NotelikeusApp touches the database type directly, and composeApp carries Room as
    // implementation, not api.
    implementation(libs.room.runtime)

    implementation(libs.androidx.appfunctions)
    implementation(libs.androidx.appfunctions.service)
    ksp(libs.androidx.appfunctions.compiler)
    
    // UI dependencies for MainActivity
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    
    // Explicitly add Compose Material3 and WindowSizeClass for Android
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.windowSizeClass)

    // This module has its own unit tests (NoteAppFunctionsTest); without these they do not
    // compile, and CI ran only :composeApp:testDebugUnitTest so the breakage stayed invisible.
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)

    // On-device only. The predictive-back opt-in in AndroidManifest.xml is an API 33+ runtime
    // behaviour: nothing about it is visible to a unit test, and it is declared here rather than
    // in :composeApp, so the test that checks it has to live beside it.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

ksp {
    arg("appfunctions:aggregateAppFunctions", "true")
}
