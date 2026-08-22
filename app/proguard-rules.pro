# ProGuard rules for Ubuntu Controller
-keepattributes *Annotation*
-keep class com.ubuntucontroller.** { *; }

# androidx.security / Tink
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
-dontwarn com.google.api.client.http.**
-dontwarn org.joda.time.**
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class com.google.crypto.tink.** { *; }

# libadb-android（muntashirakon/adb）：无线调试免弹窗配对
-keep class io.github.muntashirakon.adb.** { *; }
-keep class com.burgstaller.** { *; }
-dontwarn io.github.muntashirakon.adb.**
-dontwarn com.burgstaller.**
-dontwarn com.android.tools.desugar.**
-dontwarn okio.**
