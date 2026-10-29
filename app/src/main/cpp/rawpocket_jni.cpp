// Puente JNI delgado: la lógica vive en raw_session.* y develop.*
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <memory>
#include <string>

#include "develop.h"
#include "raw_session.h"

#define LOG_TAG "RawPocket"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::string toStd(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

}  // namespace

extern "C" {

// Abre un RAW y prepara la vista previa. Devuelve el handle (>0) o un código de error (<0).
JNIEXPORT jlong JNICALL
Java_com_rawpocket_RawNative_nativeOpen(JNIEnv* env, jobject, jstring jpath, jint previewLongEdge) {
    if (!jpath) return rp::kErrBadArgs;
    std::unique_ptr<rp::Session> session;
    int rc = rp::openSession(toStd(env, jpath), previewLongEdge, session);
    if (rc != 0) {
        LOGE("open failed (%d): %s", rc, rp::errorText(rc).c_str());
        return rc;
    }
    return reinterpret_cast<jlong>(session.release());
}

JNIEXPORT jint JNICALL
Java_com_rawpocket_RawNative_nativePreviewWidth(JNIEnv*, jobject, jlong h) {
    return h > 0 ? reinterpret_cast<rp::Session*>(h)->pw : 0;
}

JNIEXPORT jint JNICALL
Java_com_rawpocket_RawNative_nativePreviewHeight(JNIEnv*, jobject, jlong h) {
    return h > 0 ? reinterpret_cast<rp::Session*>(h)->ph : 0;
}

JNIEXPORT jstring JNICALL
Java_com_rawpocket_RawNative_nativeInfo(JNIEnv* env, jobject, jlong h) {
    if (h <= 0) return env->NewStringUTF("");
    return env->NewStringUTF(reinterpret_cast<rp::Session*>(h)->info.c_str());
}

JNIEXPORT jint JNICALL
Java_com_rawpocket_RawNative_nativeThumbnail(JNIEnv* env, jobject, jlong h, jstring jpath, jint edge) {
    if (h <= 0 || !jpath) return rp::kErrBadArgs;
    return rp::exportThumbnail(*reinterpret_cast<rp::Session*>(h), toStd(env, jpath), edge, 85);
}

JNIEXPORT jstring JNICALL
Java_com_rawpocket_RawNative_nativeErrorText(JNIEnv* env, jobject, jint code) {
    return env->NewStringUTF(rp::errorText(code).c_str());
}

// Copia la vista previa (RGB lineal) a un ByteBuffer directo como RGBA half-float, lista para
// subirla como textura GL_RGBA16F. El búfer necesita pw * ph * 8 bytes y queda independiente
// de la sesión nativa.
JNIEXPORT jboolean JNICALL
Java_com_rawpocket_RawNative_nativeFillPreviewHalf(JNIEnv* env, jobject, jlong h, jobject buffer) {
    if (h <= 0 || !buffer) return JNI_FALSE;
    auto* s = reinterpret_cast<rp::Session*>(h);
    void* dst = env->GetDirectBufferAddress(buffer);
    const jlong need = static_cast<jlong>(s->pw) * s->ph * 8;
    if (!dst || env->GetDirectBufferCapacity(buffer) < need) return JNI_FALSE;
    rp::rgb16ToRgba16f(s->preview.data(), s->pw, s->ph, static_cast<uint16_t*>(dst));
    return JNI_TRUE;
}

// Revela la vista previa dentro de un Bitmap ARGB_8888 del mismo tamaño que la vista previa.
JNIEXPORT jboolean JNICALL
Java_com_rawpocket_RawNative_nativeRender(JNIEnv* env, jobject, jlong h, jfloatArray jparams,
                                          jobject bitmap) {
    if (h <= 0 || !jparams || !bitmap) return JNI_FALSE;
    auto* s = reinterpret_cast<rp::Session*>(h);
    if (env->GetArrayLength(jparams) < rp::Params::kCount) return JNI_FALSE;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        static_cast<int>(info.width) != s->pw || static_cast<int>(info.height) != s->ph)
        return JNI_FALSE;

    float p[rp::Params::kCount];
    env->GetFloatArrayRegion(jparams, 0, rp::Params::kCount, p);

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
        return JNI_FALSE;
    rp::developToRgba(s->preview.data(), s->pw, s->ph, rp::Params::fromArray(p),
                      static_cast<uint8_t*>(pixels), static_cast<int>(info.stride),
                      rp::workerThreads());
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

// Exporta a JPEG a resolución completa (o limitada a maxEdge px si maxEdge > 0).
// Devuelve 0 si todo salió bien, o un código de error (<0).
JNIEXPORT jint JNICALL
Java_com_rawpocket_RawNative_nativeExport(JNIEnv* env, jobject, jlong h, jfloatArray jparams,
                                          jstring joutPath, jint quality, jint maxEdge) {
    if (h <= 0 || !jparams || !joutPath) return rp::kErrBadArgs;
    if (env->GetArrayLength(jparams) < rp::Params::kCount) return rp::kErrBadArgs;

    float p[rp::Params::kCount];
    env->GetFloatArrayRegion(jparams, 0, rp::Params::kCount, p);
    return rp::exportJpeg(*reinterpret_cast<rp::Session*>(h), rp::Params::fromArray(p),
                          toStd(env, joutPath), quality, maxEdge);
}

JNIEXPORT void JNICALL
Java_com_rawpocket_RawNative_nativeClose(JNIEnv*, jobject, jlong h) {
    if (h > 0) delete reinterpret_cast<rp::Session*>(h);
}

}  // extern "C"
