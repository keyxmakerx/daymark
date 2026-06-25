# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.daylie.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep the Companion of every @Serializable model so its serializer resolves under R8.
-keepclassmembers @kotlinx.serialization.Serializable class com.daylie.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.daylie.app.** {
    public static ** INSTANCE;
}

# Tink (via androidx.security-crypto) references compile-only Error Prone annotations
# that aren't on the runtime classpath. They're safe to ignore under R8.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
