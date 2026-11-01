package app.aujo.audio

/** JNI surface for the vendored whisper.cpp build (`app/src/main/cpp`). */
object WhisperJni {
    init {
        System.loadLibrary("aujo_whisper")
    }

    /** Loads a GGML model; returns a context handle (0 = failure). */
    external fun loadModel(modelPath: String): Long

    /** Releases a context handle. */
    external fun freeModel(handle: Long)

    /**
     * Transcribes 16 kHz mono float samples with `lang` ("en", "id", "auto").
     * Returns the transcript; "" if the model recognized no speech;
     * null on engine failure.
     */
    external fun transcribe(handle: Long, samples: FloatArray, lang: String): String?
}
