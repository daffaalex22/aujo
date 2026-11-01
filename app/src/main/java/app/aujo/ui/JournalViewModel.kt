package app.aujo.ui

import android.app.Application
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.aujo.audio.AudioRecorder
import app.aujo.audio.SilenceAnalyzer
import app.aujo.data.AppDatabase
import app.aujo.data.EntryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    // ---- Journal list / persistence ---------------------------------------

    val entries: StateFlow<List<EntryEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun observeEntry(id: Long) = dao.observeById(id)

    init {
        // M2: analyze any entry that predates the analyzer (or raced it).
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                dao.pendingAnalysis().forEach { analyzeAndStore(it) }
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

    private var pendingFile: File? = null

    fun startRecording() {
        if (_isRecording.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val file = newRecordingFile()
            val ok = recorder.start(file)
            if (ok) withContext(Dispatchers.Main) {
                pendingFile = file
                _startedAt.value = System.currentTimeMillis()
                _isRecording.value = true
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
                filePath = file.absolutePath
            )
            val id = dao.insert(entry)
            analyzeAndStore(entry.copy(id = id))
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

    /** Analyzes the entry's WAV and writes silence map + waveform to the DB. */
    private suspend fun analyzeAndStore(entry: EntryEntity) {
        runCatching {
            val result = SilenceAnalyzer.analyze(File(entry.filePath))
            dao.setAnalysis(
                entry.id,
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
