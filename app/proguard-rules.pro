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
