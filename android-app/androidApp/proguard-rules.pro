# Default Android proguard rules.
# Project-specific rules added as we discover them in M5/M6.

-keep class com.contextsolutions.localagent.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# play-services-tflite-gpu's GpuDelegate references org.tensorflow.lite.Delegate,
# but org.tensorflow:tensorflow-lite-api is excluded from all Android configs so
# litert's bundled org.tensorflow.lite.* stays canonical (hard invariant #18).
# This GPU-delegate path is dead at runtime (classifier/embedder use litert CPU
# XNNPACK; LiteRT-LM GPU uses play-services' own delegate), so suppress the
# R8 missing-class warning rather than re-adding the excluded dependency.
-dontwarn org.tensorflow.lite.Delegate

# Room (used internally by WorkManager for its WorkDatabase). The generated
# *_Impl databases are instantiated reflectively via their no-arg constructor
# (RoomDatabase.getGeneratedImplementation -> getDeclaredConstructor().newInstance()).
# proguard-android-optimize.txt strips that unused-looking constructor, so launch
# crashed with NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []
# (androidx.startup.InitializationProvider -> WorkManagerInitializer). Keep the
# no-arg ctor of every RoomDatabase subclass so reflection can build it.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# LiteRT native runtimes (classifier/embedder = litert #18; Gemma LLM = litertlm).
# Their .so code reaches back into Java via JNI FindClass/GetMethodID using the
# fully-qualified class names — R8 can't see those string references, so it shrank
# LiteRtException, and CompiledModel_nativeCreateFromFile aborted the process with
#   litert_jni_common.h: Check failed: ex_class != nullptr
#   Failed to find LiteRtException class
# during classifier warm-up (before the chat screen renders). Keep both packages'
# names + members so every JNI lookup resolves. (Build-time R8 can't catch this;
# only an on-device launch does — see hard invariant #70 / #40.)
-keep class com.google.ai.edge.litert.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
