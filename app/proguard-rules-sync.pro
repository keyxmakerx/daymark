# `sync` flavor only (see docs/COMPANION_PHONE_2B.md). JNA binds native methods via
# reflection at runtime, so R8 can't see those call sites — without these rules a minified
# release build can silently strip or rename classes JNA needs and fail at runtime with
# UnsatisfiedLinkError/NoSuchMethodError instead of at compile time.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * implements com.sun.jna.Library {
    public *;
}
-keepclassmembers class * implements com.sun.jna.Structure {
    public *;
}
-dontwarn com.sun.jna.**

# lazysodium-android's Native/Lazy interfaces are implemented dynamically by lazysodium's own
# native binding, and :sync-crypto's SyncCrypto is compiled against these same shared types.
-keep class com.goterl.lazysodium.** { *; }
-dontwarn com.goterl.lazysodium.**
