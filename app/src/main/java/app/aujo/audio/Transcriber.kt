package app.aujo.audio

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

enum class TranscriberStatus {
    /** Not running. */
    IDLE,

    /** Creating engine / opening the first session. */
    STARTING,

    /** A recognition session is live and listening. */
    LISTENING,

    /** Between sessions (micro-restart). */
    RESTARTING,

    /** No speech engine usable on this device. */
    UNAVAILABLE,

    /** Sessions keep failing (likely mic owned by our recorder while live). */
    FAILURE
}

/**
 * Wraps Android's [SpeechRecognizer] for continuous live transcription.
 *
 * The recognizer runs in short native sessions; for a whole journal entry we
 * chain them: on results/error the next session opens immediately, final text
 * accumulates and partial phrases stream through.
 *
 * Privacy: prefers the purely on-device engine (API 31+) when available, and
 * requests offline recognition otherwise.
 *
 * A saved WAV can also be transcribed after the fact (API 31+ audio-injection
 * API) — the fallback when the live engine can't share the microphone with
 * our recorder.
 */
class Transcriber(private val context: Context) {

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    @Volatile private var stopRequested = false
    private var lang = "en-US"
    private var errorsSinceResult = 0

    private val _committed = MutableStateFlow("")
    val committed: StateFlow<String> = _committed.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    private val _status = MutableStateFlow(TranscriberStatus.IDLE)
    val status: StateFlow<TranscriberStatus> = _status.asStateFlow()

    /** Human-readable explanation of the latest engine outcome (for UI diagnostics). */
    private val _note = MutableStateFlow("")
    val note: StateFlow<String> = _note.asStateFlow()

    // ---- Live mode ---------------------------------------------------------

    /** Starts continuous recognition. Resets accumulator — once per recording. */
    fun start(lang: String) {
        this.lang = lang
        stopRequested = false
        errorsSinceResult = 0
        _committed.value = ""
        _partial.value = ""
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _status.value = TranscriberStatus.UNAVAILABLE
            return
        }
        _status.value = TranscriberStatus.STARTING
        runOnMain {
            createEngine()
            if (recognizer == null) return@runOnMain
            beginSession()
        }
    }

    private fun createEngine() {
        if (recognizer != null) return
        recognizer = runCatching {
            if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        }.onFailure { Log.w("Aujo", "createSpeechRecognizer failed", it) }
            .getOrNull()
            ?.also { it.setRecognitionListener(listener) }
        if (recognizer == null) _status.value = TranscriberStatus.UNAVAILABLE
    }

    private fun beginSession() {
        val sr = recognizer ?: return
        _partial.value = ""
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            Log.w("Aujo", "startListening failed", e)
            recreateThen { beginSession() }
        }
    }

    /**
     * Stops live transcription; delivers accumulated text after the in-flight
     * session settles (grace period for the final phrase).
     */
    fun stop(onFinal: (String) -> Unit) {
        stopRequested = true
        runOnMain {
            try { recognizer?.stopListening() } catch (_: Exception) {}
            var t = 0
            while (t < 1200) {
                delay(100); t += 100
                // settle: give the last final result a moment to arrive
            }
            recognizer?.destroy()
            recognizer = null
            _status.value = TranscriberStatus.IDLE
            onFinal(_committed.value)
        }
    }

    // ---- Saved-audio mode (fallback) ---------------------------------------

    /**
     * Runs recognition over a saved WAV instead of the live mic (API 31+).
     *
     * DISABLED after real-device diagnosis: this phone's speech engine does
     * not honor EXTRA_AUDIO_INJECT_SOURCE — instead of reading the file it
     * opened a live microphone session (evidenced by the system mic icon),
     * i.e. it would transcribe the room, not the saved entry. Until a speech
     * engine arrives that reads files on-device, this returns null without
     * ever touching the microphone.
     */
    fun transcribeFile(path: String, lang: String, onResult: (String?) -> Unit) {
        _note.value = "This phone's speech engine ignores saved files (it opened the mic instead). Transcripts will come from the built-in engine in M5."
        _status.value = TranscriberStatus.UNAVAILABLE
        onResult(null)
    }

    // ---- internals ---------------------------------------------------------

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _status.value = TranscriberStatus.LISTENING
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onBeginningOfSpeech() {}
        override fun onEndOfSpeech() {
            if (_status.value == TranscriberStatus.LISTENING) {
                _status.value = TranscriberStatus.RESTARTING
            }
        }
        override fun onError(error: Int) {
            if (stopRequested) return
            errorsSinceResult++
            Log.d("Aujo", "transcription error $error (streak $errorsSinceResult)")
            _note.value = "Live session: engine error $error ×$errorsSinceResult"
            when {
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    recreateThen { beginSession() }
                error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    if (!stopRequested) handler.postDelayed({ beginSession() }, 120)
                errorsSinceResult >= 3 -> {
                    // e.g. the mic is owned by our recorder — live mode can't run.
                    _status.value = TranscriberStatus.FAILURE
                    Log.w("Aujo", "live transcription giving up (mic contention?)")
                }
                else -> handler.postDelayed({ beginSession() }, 250)
            }
        }
        override fun onResults(results: Bundle?) {
            errorsSinceResult = 0
            val got = firstText(results)
            if (!got.isNullOrBlank()) _committed.value = append(_committed.value, got)
            _partial.value = ""
            if (!stopRequested) handler.postDelayed({ beginSession() }, 80)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val got = firstText(partialResults)
            if (!got.isNullOrBlank()) _partial.value = got
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onLanguageDetection(params: Bundle) {}
    }

    private fun recreateThen(next: () -> Unit) {
        runOnMain {
            recognizer?.destroy()
            recognizer = null
            delay(300)
            createEngine()
            if (recognizer != null) next() else _status.value = TranscriberStatus.UNAVAILABLE
        }
    }

    private fun destroyQuietly(sr: SpeechRecognizer) {
        runCatching { sr.destroy() }
    }

    private fun append(target: StringBuilder, text: String) {
        if (target.isNotEmpty()) target.append(' ')
        target.append(text.trim())
    }

    private fun append(a: String, b: String): String =
        if (a.isBlank()) b.trim() else "${a.trim()} ${b.trim()}"

    private fun firstText(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun runOnMain(block: suspend () -> Unit) {
        mainScope.launch { block() }
    }
}
