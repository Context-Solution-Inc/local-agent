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
