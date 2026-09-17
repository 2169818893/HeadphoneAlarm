# kotlinx.serialization 反射保护（若后续开启混淆）
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.headphonealarm.**$$serializer { *; }
-keepclassmembers class com.headphonealarm.** {
    *** Companion;
}
-keepclasseswithmembers class com.headphonealarm.** {
    kotlinx.serialization.KSerializer serializer(...);
}
