# Hilt
-keep class dagger.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# SQLCipher
-keep class net.zetetic.** { *; }
-keep class net.sqlcipher.** { *; }

# Glance widget
-keep class androidx.glance.** { *; }
-keep class com.aus.notelikeus.ui.widget.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Receivers
-keep class com.aus.notelikeus.data.remote.ReminderReceiver { *; }
-keep class com.aus.notelikeus.data.remote.ReminderBootReceiver { *; }

# Keep data models used by Room
-keep class com.aus.notelikeus.data.local.entity.** { *; }
-keep class com.aus.notelikeus.domain.model.** { *; }

# JSON backup export/import
-keep class com.aus.notelikeus.data.backup.** { *; }

# Firestore cloud sync mappers
-keep class com.aus.notelikeus.data.remote.CloudIds { *; }
-keepclassmembers class com.aus.notelikeus.data.remote.** {
    public <methods>;
}

# BuildConfig (version label in settings)
-keep class com.aus.notelikeus.BuildConfig { *; }

# Navigation Compose
-keep class * extends androidx.navigation.NavType { *; }
-keepnames class androidx.navigation.** { *; }
-keepattributes *Annotation*

# Firebase
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
