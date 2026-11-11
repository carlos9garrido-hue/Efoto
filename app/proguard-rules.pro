# Los métodos nativos se resuelven por nombre desde C++ (JNI)
-keep class com.rawpocket.RawNative { *; }

# ONNX Runtime usa JNI y reflexión
-keep class ai.onnxruntime.** { *; }
