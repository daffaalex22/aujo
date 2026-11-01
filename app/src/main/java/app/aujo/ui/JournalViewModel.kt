package app.aujo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.aujo.audio.AudioRecorder
import app.aujo.data.AppDatabase
import app.aujo.data.EntryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the recorder and the journal database, shared by all screens.
 */
class JournalViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).entryDao()
    private val recorder = AudioRecorder()

    val entries: StateFlow<List<EntryEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun observeEntry(id: Long) = dao.observeById(id)

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
            withContext(Dispatchers.Main) { onSaved(entry.copy(id = id)) }
        }
    }

    fun delete(entry: EntryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.delete(entry.id)
            File(entry.filePath).delete()
        }
    }

    private fun newRecordingFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(getApplication<Application>().filesDir, "recordings/entry_$stamp.wav")
    }
}
