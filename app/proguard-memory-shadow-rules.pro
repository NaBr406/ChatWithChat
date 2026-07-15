# AndroidJUnitRunner calls target-packaged tracing before test discovery.
# This file is used only when the release instrumentation runner is explicitly overridden.
-keep class androidx.tracing.** { *; }

# The release test APK shares the target APK's Kotlin runtime, which target R8 otherwise shrinks.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

# The shadow test reflects over these target classes before production Hybrid DI is enabled.
-keep class cn.nabr.chatwithchat.data.memory.** { *; }
