# RomMDroid ProGuard rules

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.rommdroid.**$$serializer { *; }
-keepclassmembers class app.rommdroid.** {
    *** Companion;
}
-keepclasseswithmembers class app.rommdroid.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Navigation Compose type-safe routes: an enum argument's NavType is resolved
# at runtime by Class.forName on its serialName, so route classes must keep
# their fully qualified names (only their names; members may still shrink).
-keepnames class app.rommdroid.ui.navigation.Route
-keepnames class app.rommdroid.ui.navigation.Route$*
-keepnames class app.rommdroid.ui.navigation.Route$*$*

# Keep Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep interface retrofit2.** { *; }

# Keep Room entities
-keep class app.rommdroid.data.db.** { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# WorkManager
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
