-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.yoruc.mangacracker.**$$serializer { *; }
-keepclassmembers class com.yoruc.mangacracker.** { *** Companion; }
-keepclasseswithmembers class com.yoruc.mangacracker.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn org.jspecify.annotations.**
-dontwarn com.google.re2j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# the comix bridge is called from JavaScript by name
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keep class com.yoruc.mangacracker.sources.ComixBridge$Back { *; }

# ONNX Runtime is reached from its native library by name
-keep class ai.onnxruntime.** { *; }
