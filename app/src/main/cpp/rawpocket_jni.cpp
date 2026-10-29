// Puente JNI delgado: la lógica vive en raw_session.* y develop.*
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

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

// Las sesiones se entregan a Kotlin como números pequeños (1, 2, 3…), no como punteros: en Android
// arm64 los punteros del montón llevan etiquetas en el byte alto y pueden parecer negativos, y
// Kotlin usa los valores negativos como códigos de error.
std::mutex gMutex;
std::unordered_map<jlong, std::unique_ptr<rp::Session>> gSessions;
jlong gNextId = 1;

jlong registerSession(std::unique_ptr<rp::Session> s) {
    std::lock_guard<std::mutex> lock(gMutex);
    jlong id = gNextId++;
    gSessions[id] = std::move(s);
    return id;
}

// Devuelve la sesión o nullptr. Kotlin serializa el uso de cada sesión con su propio candado,
// así que no se desaloja mientras se usa (cerrar espera a ese candado).
rp::Session* lookup(jlong id) {
    std::lock_guard<std::mutex> lock(gMutex);
    auto it = gSessions.find(id);
    return it == gSessions.end() ? nullptr : it->second.get();
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
    return registerSession(std::move(session));
}

JNIEXPORT jint JNICALL
Java_com_rawpocket_RawNative_nativePreviewWidth(JNIEnv*, jobject, jlong h) {
    auto* s = lookup(h);
    return s ? s->pw : 0;
}

JNIEXPORT jint JNICALL
Java_com_rawpocket_RawNative_nativePreviewHeight(JNIEnv*, jobject, jlong h) {
    auto* s = lookup(h);
    return s ? s->ph : 0;
}

JNIEXPORT jstring JNICALL
Java_com_rawpocket_RawNative_nativeInfo(JNIEnv* env, jobject, jlong h) {
    auto* s = lookup(h);
    return env->NewStringUTF(s ? s->info.c_str() : "");
}

JNIEXPORT jint JNICALL
Java_com_rawpocket_RawNative_nativeThumbnail(JNIEnv* env, jobject, jlong h, jstring jpath, jint edge) {
    auto* s = lookup(h);
    if (!s || !jpath) return rp::kErrBadArgs;
    return rp::exportThumbnail(*s, toStd(env, jpath), edge, 85);
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
    auto* s = lookup(h);
    if (!s || !buffer) return JNI_FALSE;
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
    auto* s = lookup(h);
    if (!s || !jparams || !bitmap) return JNI_FALSE;
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
    auto* s = lookup(h);
    if (!s || !jparams || !joutPath) return rp::kErrBadArgs;
    if (env->GetArrayLength(jparams) < rp::Params::kCount) return rp::kErrBadArgs;

    float p[rp::Params::kCount];
    env->GetFloatArrayRegion(jparams, 0, rp::Params::kCount, p);
    return rp::exportJpeg(*s, rp::Params::fromArray(p),
                          toStd(env, joutPath), quality, maxEdge);
}

JNIEXPORT void JNICALL
Java_com_rawpocket_RawNative_nativeClose(JNIEnv*, jobject, jlong h) {
    std::lock_guard<std::mutex> lock(gMutex);
    gSessions.erase(h);
}

}  // extern "C"
