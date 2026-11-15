# --- WebRTC -----------------------------------------------------------------
# libwebrtc calls back into Java from native code by name; anything reachable
# only from JNI has to survive shrinking and renaming.
-keep class org.webrtc.** { *; }
-keep interface org.webrtc.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn org.webrtc.**

# --- OkHttp -----------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- App --------------------------------------------------------------------
# Keep the parcelable/serializable shapes used across the service boundary.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# --- jni_zero ---------------------------------------------------------------
# Newer WebRTC looks these up by name in JNI_OnLoad. If R8 renames them, the
# native library hits a CHECK and the process dies with SIGTRAP on load.
-keep class org.jni_zero.** { *; }
-dontwarn org.jni_zero.**
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.jni_zero.CalledByNative <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.jni_zero.CalledByNativeUnchecked <methods>;
}
-keepclasseswithmembers class * {
    @org.jni_zero.AccessedByNative <fields>;
}
