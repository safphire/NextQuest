# SPDX-License-Identifier: AGPL-3.0-or-later
# kotlinx.serialization: keep generated serializers for @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.dreamweave.quest.**$$serializer { *; }
# Ktor / OkHttp reference optional JVM classes that do not exist on Android.
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn io.ktor.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
