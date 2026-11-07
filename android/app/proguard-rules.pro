# kotlinx.serialization: keep generated serializers of the wire models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class dev.tasky.api.**$$serializer { *; }
-keepclassmembers class dev.tasky.api.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class dev.tasky.api.** {
    kotlinx.serialization.KSerializer serializer(...);
}
