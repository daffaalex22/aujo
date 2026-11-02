package app.aujo.audio

/**
 * JNI surface for the RNNoise-based denoiser (`app/src/main/cpp/denoise-jni.cpp`).
 * One denoiser object per recording: created at record start, destroyed at stop.
 * The 'handle' carries both the model state and filter histories so chunks
 * stay continuous across the recording.
 */
object DenoiseJni {
    const val MODE_LIGHT = 0      // mask noise, keep natural room tone
    const val MODE_VOICE_ONLY = 1 // additionally mute anything non-voice-shaped

    init {
        System.loadLibrary("aujo_whisper")
    }

    /** Creates a denoiser for the given recording sample rate; 0 = failure. */
    external fun nativeCreate(sampleRate: Int, mode: Int): Long

    external fun nativeDestroy(handle: Long)

    /** Processes one chunk in place semantics: returns the denoised copy (same length). */
    external fun nativeProcess(handle: Long, input: ShortArray): ShortArray

    /** {framesSeen, framesVoice} — lets the app skip whisper on silence-only recordings. */
    external fun nativeStats(handle: Long): IntArray
}
