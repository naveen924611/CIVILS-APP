# Keep our network models (kotlinx.serialization reads them by name).
-keep class com.naveen.civilscompanion.data.remote.dto.** { *; }
-keepattributes *Annotation*, InnerClasses
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
