// Aujo Whisper JNI bridge.
//
// Loads a GGML whisper model and transcribes pre-converted 16 kHz mono
// float samples. All input comes from previously recorded WAV files —
// the microphone is never touched from native code.
#include <jni.h>
#include <android/log.h>

#include <cstring>
#include <string>
#include <vector>

#include "whisper.h"

#define TAG "AujoNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// ~35% of silence between sentences; tokens beyond it are thought dead air.
static void fill_params(whisper_full_params &p) {
    p.n_threads = 4;                 // Vivo Y33s: 4x A75 big cores
    p.translate = false;
    p.print_progress = false;
    p.print_special = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.no_timestamps = true;
    p.suppress_blank = true;         // damp the classic tiny-model hallucinations
    p.suppress_nst = true;           // non-speech tokens
    p.initial_prompt = nullptr;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_aujo_audio_WhisperJni_loadModel(JNIEnv *env, jobject, jstring jPath) {
    const char *path = env->GetStringUTFChars(jPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // CPU-only build
    LOGI("loading whisper model from %s", path);
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(jPath, path);
    if (ctx == nullptr) {
        LOGW("model load failed");
        return 0;
    }
    return (jlong)(intptr_t)ctx;
}

extern "C" JNIEXPORT void JNICALL
Java_app_aujo_audio_WhisperJni_freeModel(JNIEnv *, jobject, jlong handle) {
    auto *ctx = (whisper_context *)(intptr_t)handle;
    if (ctx != nullptr) whisper_free(ctx);
}

/**
 * Transcribes `samples` (16 kHz mono float, seconds*16000 values).
 * `lang` is ISO-639-1 ("en", "id") or "auto". Returns the transcript, or
 * null on engine failure (distinct from empty = recognized nothing).
 */
extern "C" JNIEXPORT jstring JNICALL
Java_app_aujo_audio_WhisperJni_transcribe(
    JNIEnv *env, jobject, jlong handle, jfloatArray jSamples, jstring jLang) {
    auto *ctx = (whisper_context *)(intptr_t)handle;
    if (ctx == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(jSamples);
    jfloat *buf = env->GetFloatArrayElements(jSamples, nullptr);
    if (buf == nullptr) return nullptr;
    std::vector<float> samples(buf, buf + n);
    env->ReleaseFloatArrayElements(jSamples, buf, JNI_ABORT);

    const char *lang = env->GetStringUTFChars(jLang, nullptr);
    std::string langStr(lang);
    env->ReleaseStringUTFChars(jLang, lang);
    if (langStr.empty()) langStr = "auto";

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    fill_params(p);
    p.language = langStr.c_str();

    const int rc = whisper_full(ctx, p, samples.data(), (int) samples.size());
    if (rc != 0) {
        LOGW("whisper_full failed rc=%d", rc);
        return nullptr;
    }

    jboolean hasText = JNI_FALSE;
    std::string text;
    const int nSeg = whisper_full_n_segments(ctx);
    for (int i = 0; i < nSeg; ++i) {
        const char *seg = whisper_full_get_segment_text(ctx, i);
        if (seg != nullptr && seg[0] != '\0') {
            if (!text.empty()) text += ' ';
            text += seg;
            hasText = JNI_TRUE;
        }
    }
    if (!hasText) return env->NewStringUTF(""); // ran fine, recognized nothing
    return env->NewStringUTF(text.c_str());
}
