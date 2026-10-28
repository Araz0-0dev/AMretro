#include "core_host.h"
#include "libretro.h"
#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>

static pthread_mutex_t window_mutex = PTHREAD_MUTEX_INITIALIZER;
static ANativeWindow *window;
static unsigned window_width, window_height;

static void render_frame(const void *pixels, unsigned width, unsigned height, size_t pitch, unsigned format) {
    if (!pixels || !width || !height || width > 4096 || height > 4096) return;
    pthread_mutex_lock(&window_mutex);
    if (!window) { pthread_mutex_unlock(&window_mutex); return; }
    if (window_width != width || window_height != height) {
        if (ANativeWindow_setBuffersGeometry(window, (int)width, (int)height, WINDOW_FORMAT_RGBA_8888) != 0) {
            pthread_mutex_unlock(&window_mutex); return;
        }
        window_width = width; window_height = height;
    }
    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(window, &buffer, NULL) != 0) { pthread_mutex_unlock(&window_mutex); return; }
    unsigned rows = height < (unsigned)buffer.height ? height : (unsigned)buffer.height;
    unsigned cols = width < (unsigned)buffer.width ? width : (unsigned)buffer.width;
    for (unsigned y = 0; y < rows; y++) {
        uint32_t *dst = (uint32_t *)buffer.bits + (size_t)y * buffer.stride;
        const uint8_t *src = (const uint8_t *)pixels + (size_t)y * pitch;
        if (format == RETRO_PIXEL_FORMAT_XRGB8888) {
            const uint32_t *in = (const uint32_t *)src;
            for (unsigned x = 0; x < cols; x++) {
                uint32_t c = in[x];
                dst[x] = 0xff000000u | ((c & 0xff0000u) >> 16) | (c & 0x00ff00u) | ((c & 0x0000ffu) << 16);
            }
        } else {
            const uint16_t *in = (const uint16_t *)src;
            for (unsigned x = 0; x < cols; x++) {
                unsigned c = in[x], r, g, b;
                if (format == RETRO_PIXEL_FORMAT_RGB565) { r = (c >> 11) & 31; g = (c >> 5) & 63; b = c & 31; g = (g << 2) | (g >> 4); }
                else { r = (c >> 10) & 31; g = (c >> 5) & 31; b = c & 31; g = (g << 3) | (g >> 2); }
                r = (r << 3) | (r >> 2); b = (b << 3) | (b >> 2);
                dst[x] = 0xff000000u | r | (g << 8) | (b << 16);
            }
        }
    }
    ANativeWindow_unlockAndPost(window);
    pthread_mutex_unlock(&window_mutex);
}
JNIEXPORT void JNICALL Java_com_amretro_mobile_NativeEmulator_surface(JNIEnv *env, jclass cls, jobject surface) {
    (void)cls;
    ANativeWindow *next = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    pthread_mutex_lock(&window_mutex);
    if (window) ANativeWindow_release(window);
    window = next; window_width = window_height = 0;
    pthread_mutex_unlock(&window_mutex);
}
JNIEXPORT jboolean JNICALL Java_com_amretro_mobile_NativeEmulator_open(JNIEnv *env, jclass cls, jstring core, jstring rom, jstring system, jstring save) {
    (void)cls;
    if (!core || !rom || !system || !save) return JNI_FALSE;
    const char *c = (*env)->GetStringUTFChars(env, core, NULL), *r = (*env)->GetStringUTFChars(env, rom, NULL);
    const char *s = (*env)->GetStringUTFChars(env, system, NULL), *p = (*env)->GetStringUTFChars(env, save, NULL);
    int ok = c && r && s && p ? am_core_open(c, r, s, p, render_frame) : 0;
    if (c) (*env)->ReleaseStringUTFChars(env, core, c); if (r) (*env)->ReleaseStringUTFChars(env, rom, r);
    if (s) (*env)->ReleaseStringUTFChars(env, system, s); if (p) (*env)->ReleaseStringUTFChars(env, save, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jstring JNICALL Java_com_amretro_mobile_NativeEmulator_error(JNIEnv *env, jclass cls) {
    (void)cls; return (*env)->NewStringUTF(env, am_core_error());
}
JNIEXPORT void JNICALL Java_com_amretro_mobile_NativeEmulator_frame(JNIEnv *env, jclass cls, jint buttons) {
    (void)env; (void)cls; am_core_frame((uint16_t)buttons);
}
JNIEXPORT jshortArray JNICALL Java_com_amretro_mobile_NativeEmulator_audio(JNIEnv *env, jclass cls) {
    (void)cls; int16_t buffer[32768]; size_t n = am_core_take_audio(buffer, 32768);
    jshortArray result = (*env)->NewShortArray(env, (jsize)n);
    if (result && n) (*env)->SetShortArrayRegion(env, result, 0, (jsize)n, buffer);
    return result;
}
JNIEXPORT jdouble JNICALL Java_com_amretro_mobile_NativeEmulator_rate(JNIEnv *env, jclass cls) { (void)env; (void)cls; return am_core_sample_rate(); }
JNIEXPORT jdouble JNICALL Java_com_amretro_mobile_NativeEmulator_fps(JNIEnv *env, jclass cls) { (void)env; (void)cls; return am_core_fps(); }
JNIEXPORT jboolean JNICALL Java_com_amretro_mobile_NativeEmulator_saveState(JNIEnv *env, jclass cls, jstring path) {
    (void)cls; if (!path) return JNI_FALSE;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL); if (!p) return JNI_FALSE;
    int ok = am_core_save_state(p); (*env)->ReleaseStringUTFChars(env, path, p); return ok ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL Java_com_amretro_mobile_NativeEmulator_loadState(JNIEnv *env, jclass cls, jstring path) {
    (void)cls; if (!path) return JNI_FALSE;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL); if (!p) return JNI_FALSE;
    int ok = am_core_load_state(p); (*env)->ReleaseStringUTFChars(env, path, p); return ok ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL Java_com_amretro_mobile_NativeEmulator_reset(JNIEnv *env, jclass cls) { (void)env; (void)cls; return am_core_reset() ? JNI_TRUE : JNI_FALSE; }
JNIEXPORT void JNICALL Java_com_amretro_mobile_NativeEmulator_close(JNIEnv *env, jclass cls) { (void)env; (void)cls; am_core_close(); }
