# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep reactive streams dependencies
-keep class reactor.** { *; }
-keep class io.micrometer.** { *; }
-dontwarn io.micrometer.**
-dontwarn reactor.**

# Ignore missing optional logging dependencies used by Netty
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**

# The release compatibility harness calls this stable entry point after R8.
-keep class cn.nabr.chatwithchat.data.memory.vector.MemoryVectorReleaseCompatibilityProbe {
    public static *;
}

# ObjectBox JNI constructs scored query results by their binary class name.
-keep class io.objectbox.query.ObjectWithScore { *; }

# ONNX Runtime JNI resolves tensor/value metadata classes by their binary names.
-keep class ai.onnxruntime.** { *; }

# SnakeYAML's TypeDescription static initializer calls Class.getPackage().getName().
# R8 obfuscation removes the package metadata on Android and makes getPackage() return null.
# Keep only that class's original name; keeping the whole desktop-oriented library also pulls in
# optional java.beans references that are not present on Android.
-keep class org.yaml.snakeyaml.TypeDescription { *; }
-dontwarn java.beans.**

# pdfbox-android loads fonts, CMaps and the Bouncy Castle bits by resource path and reflection,
# so its classes must keep their original names. The optional AWT/desktop references it carries
# do not exist on Android and are unreachable from the text-extraction path we use.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.tom_roush.fontbox.**
-dontwarn com.tom_roush.harmony.**
-dontwarn org.bouncycastle.**
-dontwarn javax.imageio.**

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
