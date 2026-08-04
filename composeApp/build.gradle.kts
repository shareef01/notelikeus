import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    android {
        namespace = "com.aus.notelikeus.shared"
        compileSdk = 37
        minSdk = 26
        
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    jvm("desktop")
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
                implementation(compose.materialIconsExtended)
                
                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(libs.koin.compose.viewmodel)
                
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.room.runtime)
                implementation(libs.androidx.sqlite)
                
                // Navigation
                implementation(libs.androidx.navigation.compose)
                
                // Adaptive
                implementation(libs.androidx.compose.material3.windowSizeClass)
                implementation(libs.androidx.compose.adaptive)
                implementation(libs.androidx.compose.adaptive.layout)
                implementation(libs.androidx.compose.adaptive.navigation)
            }
        }
        
        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.lifecycle.runtime.ktx)
                implementation(libs.androidx.lifecycle.viewmodel.compose)
                implementation(libs.kotlinx.coroutines.android)
                
                implementation(libs.koin.android)
                
                implementation(libs.androidx.biometric)
                implementation(libs.androidx.datastore.preferences)
                
                // Firebase
                implementation(project.dependencies.platform(libs.firebase.bom))
                implementation(libs.firebase.auth)
                implementation(libs.firebase.firestore)
                implementation(libs.firebase.appcheck.playintegrity)
                implementation(libs.play.services.auth)
                implementation(libs.kotlinx.coroutines.play.services)
                
                // WorkManager
                implementation(libs.androidx.work.runtime.ktx)
                
                implementation(libs.sqlcipher.android)
                implementation(libs.androidx.security.crypto)
            }
        }
        
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.aus.notelikeus.main.kt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Notelikeus"
            packageVersion = "1.0.0"
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspDesktop", libs.room.compiler)
}
