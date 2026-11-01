package app.aujo.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One journal entry.
 *
 * `transcript` and `silenceJson` are nullable placeholders so that
 * M2 (silence map) and M3 (transcription) can fill them in without
 * needing a Room schema migration.
 */
@Entity(tableName = "entries")
data class EntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Wall-clock time the recording was started, epoch millis. */
    val createdAt: Long,
    /** Audio duration in milliseconds. */
    val durationMs: Long,
    /** Absolute path of the WAV file in app-private storage. */
    val filePath: String,
    /** M3: plain-text transcription. */
    val transcript: String? = null,
    /** M2: silence map, JSON list of [startMs, endMs] pairs. */
    val silenceJson: String? = null,
    /** M2: loudness per 100 ms frame (0..1), JSON array — drives the waveform. */
    val waveformJson: String? = null,
    /** Version of the analyzer that produced the current map. 0 = legacy. */
    val analysisVersion: Int = 0,
)
