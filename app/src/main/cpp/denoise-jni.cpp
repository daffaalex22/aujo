// Aujo RNNoise bridge.
//
// Our capture is 16 kHz mono; the classic Xiph RNNoise runs at 48 kHz with
// 480-sample (10 ms) frames. A small windowed-sinc resampler wraps the model,
// so one 100 ms recording chunk goes:
//   16 kHz -> upsample 3x -> rnnoise frames (voice gate in "voice-only"
//   mode) -> downsample 3x -> 16 kHz.
//
// The voice gate rides on the voice-probability that rnnoise_process_frame
// returns per frame: frames the model considers noise-shaped fade toward
// silence, with short smoothing so word onsets survive.
#include <jni.h>
#include <android/log.h>

#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <new>

#include "rnnoise.h"

#define TAG "AujoNative"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace aujo {

const int kInChunk = 1600;               // 100 ms at 16 kHz (recorder read size)
const int kFactor = 3;
const int kUpChunk = kInChunk * kFactor; // 4800 samples at 48 kHz
const int kFrame48 = 480;                // RNNoise frame: 10 ms at 48 kHz
const int kFramesPerChunk = kUpChunk / kFrame48;

// Voice-only gate shaping: frames whose voice-probability is below
// kProbOff fade to silence, above kProbOn pass fully. Per-frame smoothing
// gives inaudible transitions under speech yet decisive muting under noise.
const float kProbOn = 0.72f;
const float kProbOff = 0.55f;
const float kAttack = 0.35f;   // gate approach speed when voice appears
const float kRelease = 0.55f;  // ... when it disappears

// Hann-windowed sinc kernel, normalized (DC gain 1).
static void designKernel(float *taps, int n, double nyquistRatio) {
    const double center = (n - 1) / 2.0;
    double sum = 0.0;
    for (int k = 0; k < n; ++k) {
        const double x = k - center;
        const double sinc = (std::fabs(x) < 1e-9)
            ? 2.0 * nyquistRatio
            : 2.0 * nyquistRatio * std::sin(M_PI * nyquistRatio * x) / (M_PI * nyquistRatio * 2.0 * x);
        const double w = 0.5 - 0.5 * std::cos(2.0 * M_PI * k / (n - 1));
        taps[k] = (float)(sinc * w);
        sum += taps[k];
    }
    if (std::fabs(sum) < 1e-9) sum = 1.0;
    for (int k = 0; k < n; ++k) taps[k] = (float)(taps[k] / sum);
}

// 3-phase stage for 3x upsampling: y[3m+s] = Σ phase[s][p] · x[m−p].
// Each phase is normalized independently (only one phase consumes any
// given input sample).
struct Poly3 {
    int P = 0;
    float phase0[12] = {};
    float phase1[12] = {};
    float phase2[12] = {};
    float hist[12] = {};    // last P−1 inputs, ring
    int histPos = 0;
    int seen = 0;
};

// Full-kernel stage for 3x decimation: z[m] = Σ taps[j] · v[3m−j].
struct Dec3 {
    int T = 0;
    float taps[63] = {};
    float hist[63] = {};    // ring of last T 48 kHz samples
    int histPos = 0;
    int seen = 0;
};

struct AujoDenoiser {
    DenoiseState *st = nullptr;
    int voiceOnly = 0;
    Poly3 up;
    Dec3 down;
    float in48[kUpChunk];
    float out48[kUpChunk];
    float gate = 1.0f;      // smoothed voice gate (1 = pass)
    long framesSeen = 0;    // for the "was there any voice at all?" gate
    long framesVoice = 0;   // frames with voice-probability > 0.5
};

} // namespace aujo

extern "C" JNIEXPORT jlong JNICALL
Java_app_aujo_audio_DenoiseJni_nativeCreate(JNIEnv *env, jobject, jint sampleRate, jint mode) {
    using namespace aujo;
    if (sampleRate != 16000) { LOGW("denoise: unsupported rate %d", sampleRate); return 0; }
    auto *d = new (std::nothrow) AujoDenoiser();
    if (d == nullptr) return 0;
    d->voiceOnly = (mode >= 1) ? 1 : 0;
    d->st = rnnoise_create(nullptr);
    if (d->st == nullptr) { delete d; return 0; }

    d->up.P = 12;
    {
        float full[36];
        designKernel(full, 36, 0.50); // pass ≤8 kHz content (input band), kill zero-stuff images ≥16 kHz... safe margin
        const int P = d->up.P;
        for (int p = 0; p < P; ++p) {
            d->up.phase0[p] = full[3 * p + 0];
            d->up.phase1[p] = full[3 * p + 1];
            d->up.phase2[p] = full[3 * p + 2];
        }
        // per-phase DC normalization
        float sums[3] = {0, 0, 0};
        for (int p = 0; p < P; ++p) {
            sums[0] += d->up.phase0[p];
            sums[1] += d->up.phase1[p];
            sums[2] += d->up.phase2[p];
        }
        for (int p = 0; p < P; ++p) {
            d->up.phase0[p] = (float)(d->up.phase0[p] / (std::fabs(sums[0]) < 1e-9 ? 1.0 : sums[0]));
            d->up.phase1[p] = (float)(d->up.phase1[p] / (std::fabs(sums[1]) < 1e-9 ? 1.0 : sums[1]));
            d->up.phase2[p] = (float)(d->up.phase2[p] / (std::fabs(sums[2]) < 1e-9 ? 1.0 : sums[2]));
        }
    }
    d->down.T = 63;
    designKernel(d->down.taps, 63, 0.33); // anti-alias at ~7.9 kHz before decimating
    return (jlong)(intptr_t) d;
}

extern "C" JNIEXPORT void JNICALL
Java_app_aujo_audio_DenoiseJni_nativeDestroy(JNIEnv *env, jobject, jlong h) {
    auto *d = (aujo::AujoDenoiser *)(intptr_t) h;
    if (d == nullptr) return;
    if (d->st) rnnoise_destroy(d->st);
    delete d;
}

static inline jshort clamp16(float f) {
    if (f >= 32766.0f) return 32767;
    if (f <= -32766.0f) return -32767;
    return (jshort) lrintf(f);
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_app_aujo_audio_DenoiseJni_nativeProcess(
    JNIEnv *env, jobject, jlong h, jshortArray jIn) {
    using namespace aujo;
    auto *d = (AujoDenoiser *)(intptr_t) h;
    jsize n = env->GetArrayLength(jIn);
    jshortArray jOut = env->NewShortArray(n);
    if (n <= 0) return jOut;

    jshort *in = env->GetShortArrayElements(jIn, nullptr);
    if (in == nullptr) return jOut;

    if (d == nullptr || n > kInChunk) {
        // Defensive passthrough (never happens via the recorder's fixed chunks).
        env->SetShortArrayRegion(jOut, 0, n, in);
        env->ReleaseShortArrayElements(jIn, in, JNI_ABORT);
        return jOut;
    }

    // short → float (also bounded by safety)
    float x16[kInChunk];
    for (int i = 0; i < n; ++i) x16[i] = in[i] / 32768.0f;

    // --- upsample 3x (polyphase, streaming continuity via ring history) ---
    for (int m = 0; m < n; ++m) {
        d->up.hist[d->up.histPos] = x16[m];
        d->up.histPos = (d->up.histPos + 1) % d->up.P;
        d->up.seen++;
        const float *ph[3] = {d->up.phase0, d->up.phase1, d->up.phase2};
        for (int s = 0; s < 3; ++s) {
            float acc = 0.0f;
            int idx = d->up.histPos; // oldest first: hist[histPos] holds the oldest kept sample
            for (int p = 0; p < d->up.P; ++p) {
                acc += ph[s][p] * d->up.hist[(idx + p) % d->up.P];
            }
            d->in48[3 * m + s] = acc;
        }
    }

    // --- rnnoise frames ---
    float prevGate = d->gate;
    for (int f = 0; f < kFramesPerChunk; ++f) {
        const float *inF = d->in48 + f * kFrame48;
        float *outF = d->out48 + f * kFrame48;
        const float vad = rnnoise_process_frame(d->st, outF, inF);
        d->framesSeen++;
        if (vad > 0.5f) d->framesVoice++;
        if (d->voiceOnly) {
            float target = (vad - kProbOff) / (kProbOn - kProbOff);
            if (target < 0.0f) target = 0.0f;
            if (target > 1.0f) target = 1.0f;
            d->gate += (target - d->gate) * (target > d->gate ? kAttack : kRelease);
            const float step = (d->gate - prevGate) / (float) kFrame48;
            for (int i = 0; i < kFrame48; ++i) {
                outF[i] *= prevGate + step * (float) i;
            }
            prevGate = d->gate;
        }
    }

    // --- downsample 3x (full-kernel FIR over a ring of 48 kHz samples) ---
    for (int m = 0; m < n; ++m) {
        for (int c = 0; c < kFactor; ++c) {
            d->down.hist[d->down.histPos] = d->out48[3 * m + c];
            d->down.histPos = (d->down.histPos + 1) % d->down.T;
            d->down.seen++;
        }
        if (d->down.seen < d->down.T) {
            x16[m] = 0.0f; // warm-up: undetermined until one full kernel window arrived
            continue;
        }
        float acc = 0.0f;
        for (int lag = 0; lag < d->down.T; ++lag) {
            // lag 0 = the most recent sample written; taps are time-ordered,
            // so weight for lag L is taps[T-1-L]
            acc += d->down.taps[d->down.T - 1 - lag]
                 * d->down.hist[(d->down.histPos + d->down.T - 1 - lag) % d->down.T];
        }
        x16[m] = acc;
    }

    // back to shorts
    jshort out[kInChunk];
    for (int i = 0; i < n; ++i) out[i] = clamp16(x16[i] * 32768.0f);
    env->SetShortArrayRegion(jOut, 0, n, out);
    env->ReleaseShortArrayElements(jIn, in, JNI_ABORT);
    return jOut;
}

/** {framesSeen, framesVoice} — lets the app skip whisper on silence-only recordings. */
extern "C" JNIEXPORT jintArray JNICALL
Java_app_aujo_audio_DenoiseJni_nativeStats(JNIEnv *env, jobject, jlong h) {
    auto *d = (aujo::AujoDenoiser *)(intptr_t) h;
    jint arr[2] = {0, 0};
    if (d != nullptr) {
        arr[0] = (jint) d->framesSeen;
        arr[1] = (jint) d->framesVoice;
    }
    jintArray out = env->NewIntArray(2);
    env->SetIntArrayRegion(out, 0, 2, arr);
    return out;
}
