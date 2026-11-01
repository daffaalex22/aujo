package app.aujo.ui

import android.app.Application
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.aujo.audio.AudioRecorder
import app.aujo.audio.SilenceAnalyzer
import app.aujo.audio.Transcriber
import app.aujo.audio.TranscriberStatus
import app.aujo.audio.WhisperEngine
import app.aujo.audio.WhisperModelManager
import app.aujo.data.AppDatabase
import app.aujo.data.EntryEntity
import app.aujo.data.TranscriptState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the recorder, the database and (since M2) the audio player with
 * silence skipping — shared by all screens.
 */
class JournalViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).entryDao()
    private val recorder = AudioRecorder()
    private val transcriber = Transcriber(app)
    private val modelManager = WhisperModelManager(app)
    private var whisperEngine: WhisperEngine? = null
    private val prefs = app.getSharedPreferences("aujo", android.content.Context.MODE_PRIVATE)

    // ---- Journal list / persistence ---------------------------------------

    val entries: StateFlow<List<EntryEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun observeEntry(id: Long) = dao.observeById(id)

    data class TranscribeRequest(val entryId: Long, val force: Boolean)

    /** Single background consumer: transcriptions run one at a time. */
    private val transcribeQueue = Channel<TranscribeRequest>(Channel.UNLIMITED)

    init {
        // Re-analyze entries whose maps predate the current analyzer version.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                dao.pendingAnalysis(SilenceAnalyzer.ANALYSIS_VERSION).forEach { analyzeAndStore(it) }
            }
        }
        // A previous process could have died mid-transcription; mark those
        // honestly failed so the user can retry manually.
        viewModelScope.launch(Dispatchers.IO) { dao.failStaleTranscripts() }
        // Serial (not parallel) so long entries don't stack CPU load.
        viewModelScope.launch(Dispatchers.Default) {
            for (req in transcribeQueue) {
                runWhisperJob(req)
            }
        }
    }

    // ---- Recording ---------------------------------------------------------

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _startedAt = MutableStateFlow(0L)
    val startedAt: StateFlow<Long> = _startedAt.asStateFlow()

    /** Live loudness for the record screen's meter. */
    val level: StateFlow<Float> = recorder.level

    // ---- Transcription state (M3) ------------------------------------------

    /** Text finalized so far in the current recording. */
    val transcriptText: StateFlow<String> = transcriber.committed

    /** Phrase currently being spoken (not final yet). */
    val transcriptPartial: StateFlow<String> = transcriber.partial

    val transcriberStatus: StateFlow<TranscriberStatus> = transcriber.status

    /** Latest transcription-engine outcome surfaced for diagnostics. */
    val transcribeNote: StateFlow<String> = transcriber.note

    // ---- Whisper model + background queue (M3) -----------------------------

    val whisperModelState: StateFlow<WhisperModelManager.ModelState> = modelManager.state

    /** Entry id currently being transcribed, if any. */
    private val _transcribingEntryId = MutableStateFlow<Long?>(null)
    val transcribingEntryId: StateFlow<Long?> = _transcribingEntryId.asStateFlow()

    fun downloadWhisperModel() {
        viewModelScope.launch { modelManager.download() }
    }

    fun removeWhisperModel() {
        whisperEngine?.close()
        whisperEngine = null
        modelManager.delete()
    }

    fun isWhisperModelReady(): Boolean = modelManager.isReady()

    private suspend fun engineReady(): WhisperEngine? {
        whisperEngine?.let { engine -> if (engine.load()) return engine }
        val file = WhisperModelManager.modelFile(getApplication())
        if (!file.exists()) return null
        return try {
            WhisperEngine(file).also { engine ->
                if (!engine.load()) {
                    engine.close()
                    return null
                }
                whisperEngine = engine
            }
        } catch (e: Exception) {
            Log.w("Aujo", "whisper engine init failed", e)
            null
        }
    }

    /** Queues an entry for offline transcription (after save / manual retry). */
    fun requestWhisperTranscribe(entry: EntryEntity) {
        viewModelScope.launch(Dispatchers.IO) { enqueueTranscribe(entry.id) }
    }

    private suspend fun enqueueTranscribe(id: Long, force: Boolean = false) {
        val a = dao.byId(id) ?: return
        if (a.transcriptState == 1 || a.transcriptState == 2) return
        if (!force && !a.transcript.isNullOrBlank()) return
        dao.setTranscriptState(id, TranscriptState.QUEUED, null)
        transcribeQueue.send(TranscribeRequest(id, force))
    }

    /** One queued transcription job: model-in → transcript-out, never the mic. */
    private suspend fun runWhisperJob(req: TranscribeRequest) {
        val id = req.entryId
        val app = getApplication<Application>()
        try {
            val entry = dao.byId(id)
            if (entry == null || (!req.force && !entry.transcript.isNullOrBlank())) return
            if (!modelManager.isReady()) {
                dao.setTranscriptState(id, TranscriptState.FAILED, "Model is not downloaded yet")
                return
            }
            dao.setTranscriptState(id, TranscriptState.RUNNING, null)
            _transcribingEntryId.value = id
            val engine = engineReady()
            if (engine == null) {
                dao.setTranscriptState(id, TranscriptState.FAILED, "Model failed to load")
                return
            }
            val lang = when (entry.language.ifBlank { "auto" }.take(2).lowercase()) {
                "en" -> "en"
                "id" -> "id"
                else -> "auto"
            }
            val text = withContext(Dispatchers.IO) {
                engine.transcribe(File(entry.filePath), lang)
            }
            when {
                text == null ->
                    dao.setTranscriptState(id, TranscriptState.FAILED, "Transcription engine failed")
                text.isBlank() ->
                    dao.setTranscriptState(id, TranscriptState.FAILED, "No speech recognized (recording may be silent)")
                else -> dao.setTranscript(id, text.trim(), "whisper", TranscriptState.DONE, null)
            }
        } catch (e: Exception) {
            Log.w("Aujo", "whisper job failed", e)
            try { dao.setTranscriptState(id, TranscriptState.FAILED, e.message ?: "transcription failed") } catch (_: Exception) {}
        } finally {
            _transcribingEntryId.value = null
        }
    }

    private val _activeLang = MutableStateFlow(loadLangPref())
    val activeLang: StateFlow<String> = _activeLang.asStateFlow()

    fun setLanguage(lang: String) {
        _activeLang.value = lang
        prefs.edit().putString("recordingLang", lang).apply()
    }

    private fun loadLangPref(): String =
        prefs.getString("recordingLang", "en-US") ?: "en-US"

    private var pendingFile: File? = null

    fun startRecording() {
        if (_isRecording.value) return
        val lang = _activeLang.value
        viewModelScope.launch(Dispatchers.IO) {
            val file = newRecordingFile()
            val ok = recorder.start(file)
            if (ok) withContext(Dispatchers.Main) {
                pendingFile = file
                _startedAt.value = System.currentTimeMillis()
                _isRecording.value = true
                // Live transcription runs beside the recorder if the engine allows.
                transcriber.start(lang)
            } else {
                file.delete()
            }
        }
    }

    /**
     * Stops the recorder and persists the entry. Calls [onSaved] with the
     * saved entry (or null if nothing usable was recorded).
     * Silence/waveform analysis continues in the background afterwards.
     */
    fun stopRecording(onSaved: (EntryEntity?) -> Unit) {
        if (!_isRecording.value) {
            onSaved(null)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val duration = recorder.stop()
            val file = pendingFile
            val started = _startedAt.value

            pendingFile = null
            _isRecording.value = false
            _startedAt.value = 0

            if (file == null || duration < 300) {
                file?.delete()
                withContext(Dispatchers.Main) { onSaved(null) }
                return@launch
            }

            val entry = EntryEntity(
                createdAt = started,
                durationMs = duration,
                filePath = file.absolutePath,
                language = _activeLang.value
            )
            val id = dao.insert(entry)
            analyzeAndStore(entry.copy(id = id))

            // Transcript: live engine's text if it produced any, otherwise the
            // saved file goes through the offline Whisper queue.
            viewModelScope.launch(Dispatchers.Main) {
                transcriber.stop { liveText ->
                    viewModelScope.launch(Dispatchers.IO) {
                        if (liveText.isNotBlank()) {
                            dao.setTranscript(id, liveText, "live", TranscriptState.DONE, null)
                        } else {
                            enqueueTranscribe(id)
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) { onSaved(entry.copy(id = id)) }
        }
    }

    fun delete(entry: EntryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            if (entry.id == playheadEntryId) closePlayer()
            dao.delete(entry.id)
            File(entry.filePath).delete()
        }
    }

    /** Re-runs offline transcription for an entry (manual "Transcribe"/retry). */
    fun retryTranscribe(entry: EntryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val missingModel = !modelManager.isReady()
            if (missingModel) {
                dao.setTranscriptState(
                    entry.id, TranscriptState.FAILED, "Model is not downloaded yet"
                )
                return@launch
            }
            enqueueTranscribe(entry.id, force = true)
        }
    }

    /** Analyzes the entry's WAV and writes silence map + waveform to the DB. */
    private suspend fun analyzeAndStore(entry: EntryEntity) {
        runCatching {
            val result = SilenceAnalyzer.analyze(File(entry.filePath))
            dao.setAnalysis(
                entry.id,
                SilenceAnalyzer.ANALYSIS_VERSION,
                SilenceAnalyzer.encodeSilences(result.silences),
                SilenceAnalyzer.encodeLevels(result.levels)
            )
        }.onFailure { Log.w("Aujo", "analysis failed for ${entry.filePath}", it) }
    }

    private fun newRecordingFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(getApplication<Application>().filesDir, "recordings/entry_$stamp.wav")
    }

    // ---- Playback (M2: with silence skipping) ------------------------------

    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private var playheadEntryId: Long = -1L
    private var silenceRanges: List<Pair<Long, Long>> = emptyList()
    private var durationMs: Long = 0

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _positionSec = MutableStateFlow(0)
    val positionSec: StateFlow<Int> = _positionSec.asStateFlow()

    private val _skipSilence = MutableStateFlow(true)
    val skipSilence: StateFlow<Boolean> = _skipSilence.asStateFlow()

    /**
     * Prepares the player for [entry]. No-op if that entry is already loaded.
     * If silence analysis lands while we're loaded, the map is refreshed live.
     */
    fun openEntry(entry: EntryEntity?) {
        val e = entry ?: return
        if (playheadEntryId == e.id && player != null) {
            // same entry — just pick up a freshly analyzed map if we had none
            if (silenceRanges.isEmpty() && !e.silenceJson.isNullOrBlank()) {
                silenceRanges = SilenceAnalyzer.decodeSilences(e.silenceJson)
            }
            return
        }
        closePlayer()
        playheadEntryId = e.id
        durationMs = e.durationMs
        silenceRanges = SilenceAnalyzer.decodeSilences(e.silenceJson)

        val p = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(e.filePath)
            prepare()
            setOnCompletionListener {
                _playing.value = false
                _positionSec.value = 0
                player?.seekTo(0)
            }
        }
        player = p

        ticker?.cancel()
        ticker = viewModelScope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(200)
                val mp = player ?: continue
                if (!_playing.value) continue
                var pos = mp.currentPosition.coerceIn(0, durationMs.toInt())
                if (_skipSilence.value) {
                    val hit = silenceRanges.firstOrNull { pos >= it.first && pos < it.second }
                    if (hit != null) {
                        Log.d("Aujo", "skip silence ${pos}ms -> ${hit.second}ms")
                        mp.seekTo(hit.second.toInt())
                        pos = hit.second.toInt()
                    }
                }
                _positionSec.value = pos / 1000
            }
        }
    }

    fun closePlayer() {
        ticker?.cancel()
        ticker = null
        player?.let { p ->
            try { if (p.isPlaying) p.stop() } catch (_: Exception) {}
            try { p.release() } catch (_: Exception) {}
        }
        player = null
        _playing.value = false
        _positionSec.value = 0
        playheadEntryId = -1
        silenceRanges = emptyList()
        durationMs = 0
    }

    fun togglePlay() {
        val p = player ?: return
        if (_playing.value) {
            p.pause()
            _playing.value = false
        } else {
            p.start()
            _playing.value = true
        }
    }

    fun seekToSec(sec: Int) {
        val p = player ?: return
        val ms = (sec * 1000).coerceIn(0, durationMs.toInt())
        p.seekTo(ms)
        _positionSec.value = ms / 1000
    }

    fun setSkipSilence(enabled: Boolean) {
        _skipSilence.value = enabled
    }
}
