import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    androidTarget {
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
                implementation(compose.animation)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
                implementation(compose.materialIconsExtended)
                
                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(libs.koin.compose.viewmodel)

                implementation(libs.compose.lifecycle.runtime)
                implementation(libs.compose.lifecycle.viewmodel)
                
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.room.runtime)
                implementation(libs.androidx.sqlite)
                implementation(libs.androidx.datastore.preferences.core)
                implementation(libs.kotlinx.serialization.json)
                implementation("com.squareup.okio:okio:3.9.1")
                
                implementation(libs.compose.navigation)
                
                implementation(libs.compose.material3.windowSizeClass)
                implementation(libs.compose.adaptive)
                implementation(libs.compose.adaptive.layout)
                implementation(libs.compose.adaptive.navigation)
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
                implementation(libs.androidx.datastore.preferences.android)
                implementation(project.dependencies.platform(libs.firebase.bom))
                implementation(libs.firebase.auth)
                implementation(libs.firebase.firestore)
                implementation(libs.firebase.appcheck.playintegrity)
                implementation(libs.play.services.auth)
                implementation(libs.kotlinx.coroutines.play.services)
                implementation(libs.androidx.credentials)
                implementation(libs.androidx.credentials.play.services.auth)
                implementation(libs.google.identity.googleid)
                implementation(libs.androidx.work.runtime.ktx)
                implementation(libs.sqlcipher.android)
                implementation(libs.androidx.security.crypto)
                implementation(libs.androidx.glance.appwidget)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.core)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
                implementation("androidx.sqlite:sqlite-bundled:2.5.0-alpha13")
                implementation("net.java.dev.jna:jna:5.14.0")
                implementation("net.java.dev.jna:jna-platform:5.14.0")
            }
        }
    }
}

android {
    namespace = "com.aus.notelikeus.shared"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
    }
    buildFeatures {
        buildConfig = true
    }
}

compose.desktop {
    application {
        mainClass = "com.aus.notelikeus.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Notelikeus"
            packageVersion = "1.0.0"
            description = "Elite Cross-Platform Note Taking"
            copyright = "© 2026 Aus"
            vendor = "Aus"
            
            windows {
                shortcut = true
                menu = true
                upgradeUuid = "d738f712-4217-485c-9c71-0816999b7082"
            }
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
