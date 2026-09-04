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

# Tink (via androidx.security-crypto) references Error Prone annotations that are
# compile-only and not on the runtime classpath.
-dontwarn com.google.errorprone.annotations.**
