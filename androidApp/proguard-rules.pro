# R8 rules for Notelikeus release builds.
#
# Most dependencies (Room, Firebase, Compose, WorkManager, androidx) ship consumer rules in
# their AARs, so only the gaps are listed here. Pair any change to this file with an on-device
# smoke test of: cloud sign-in + sync, note edit + reminder scheduling, the glance widget, and
# AppFunctions — the four reflection-adjacent surfaces.

# SQLCipher is accessed through JNI; R8 cannot see native references from C.
-keep class net.zetetic.database.sqlcipher.** { *; }

# kotlinx.serialization: serializers are looked up reflectively for backup JSON (de)serialization.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.aus.notelikeus.**$$serializer { *; }
-keepclassmembers class com.aus.notelikeus.** { *** Companion; }
-keepclasseswithmembers class com.aus.notelikeus.** { kotlinx.serialization.KSerializer serializer(...); }

# Koin resolves definitions at runtime; the module DSL is reflection-free for constructor
# lambdas, but qualifiers and named args benefit from keeping parameter metadata.
-keep class org.koin.** { *; }
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# Glance app widgets and AppFunctions services are manifest/KSP-referenced; AGP keeps manifest
# components itself, so nothing extra needed beyond silencing the verifier noise from their
# generated code.
-dontwarn androidx.glance.**
-dontwarn androidx.appfunctions.**
