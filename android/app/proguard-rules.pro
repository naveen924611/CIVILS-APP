# Keep our network models (kotlinx.serialization reads them by name).
-keep class com.naveen.civilscompanion.data.remote.dto.** { *; }
-keep class com.naveen.civilscompanion.data.model.** { *; }
-keepattributes *Annotation*, InnerClasses
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
# Workers and receivers are created by name by Android / WorkManager.
-keep class com.naveen.civilscompanion.sync.SyncWorker { *; }
