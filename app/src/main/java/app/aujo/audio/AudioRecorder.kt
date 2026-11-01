package app.aujo.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Records microphone audio to a 16 kHz mono 16-bit WAV file.
 *
 * Runs a dedicated read loop on a background thread while recording.
 * Exposes a 0..1 loudness [level] flow (100 ms granularity) for UI meters,
 * and should be stopped via [stop], which finalizes the WAV header and
 * returns the total recorded duration in milliseconds.
 *
 * This is the raw capture layer of the app. Silence *detection*
 * (M2) will observe the same level data later — it deliberately
 * does not alter what is written to disk.
 */
class AudioRecorder {

    /** Speech-recognition-friendly sample rate: no resample needed later. */
    val sampleRate = 16_000

    private val _level = MutableStateFlow(0f)

    /** Loudness normalized to 0..1, updated about every 100 ms while recording. */
    val level: StateFlow<Float> get() = _level

    @Volatile private var recordThread: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var pendingFile: RandomAccessFile? = null
    @Volatile private var framesWritten = 0L
    private var audioRecord: AudioRecord? = null

    /**
     * Starts recording into [target] (a .wav path).
     * Returns false if the microphone could not be initialized.
     */
    @SuppressLint("MissingPermission") // caller is responsible for RECORD_AUDIO
    fun start(target: File): Boolean {
        if (recordThread != null) return false

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false

        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 4
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }

        target.parentFile?.mkdirs()
        val file = try {
            RandomAccessFile(target, "rw").also {
                it.setLength(0)
                it.write(ByteArray(44)) // WAV header placeholder, patched on stop()
                pendingFile = it
            }
        } catch (e: Exception) {
            rec.release()
            return false
        }

        framesWritten = 0
        stopRequested = false
        audioRecord = rec
        rec.startRecording()
        recordThread = thread(name = "aujo-recorder") { readLoop(rec, file) }
        return true
    }

    private fun readLoop(rec: AudioRecord, file: RandomAccessFile) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val chunk = ShortArray(sampleRate / 10) // 100 ms of audio
        val bytes = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)

        while (!stopRequested) {
            val n = rec.read(chunk, 0, chunk.size)
            if (n <= 0) continue
            bytes.clear()
            for (i in 0 until n) bytes.putShort(chunk[i])
            file.write(bytes.array(), 0, n * 2)
            framesWritten += n
            _level.value = normalizedLevel(chunk, n)
        }
        _level.value = 0f
    }

    /**
     * Stops recording, finalizes the WAV header, and returns the duration
     * in milliseconds. Safe to call even if [start] failed or was never called.
     */
    fun stop(): Long {
        stopRequested = true
        recordThread?.let { t -> try { t.join(2_000) } catch (_: InterruptedException) {} }
        recordThread = null

        audioRecord?.let { rec ->
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        audioRecord = null

        pendingFile?.let { file ->
            try {
                finalizeWavHeader(file, sampleRate, framesWritten)
            } catch (_: Exception) {
            }
            try { file.close() } catch (_: Exception) {}
        }
        pendingFile = null

        return framesWritten * 1_000 / sampleRate
    }

    /** RMS mapped from -50 dB (silence) to 0 dB (full scale) onto 0..1. */
    private fun normalizedLevel(buf: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) {
            val s = buf[i].toDouble()
            sum += s * s
        }
        val rms = sqrt(sum / n)
        if (rms < 1.0) return 0f
        val db = 20 * log10(rms / 32_767.0)
        return (((db + 50.0) / 50.0).coerceIn(0.0, 1.0)).toFloat()
    }

    private fun finalizeWavHeader(file: RandomAccessFile, sampleRate: Int, totalFrames: Long) {
        val dataBytes = totalFrames * 2 // mono, 16-bit
        val byteRate = sampleRate * 2

        file.seek(0)
        file.writeBytes("RIFF"); file.writeIntLe((36 + dataBytes).toInt())
        file.writeBytes("WAVE")
        file.writeBytes("fmt "); file.writeIntLe(16)
        file.writeShortLe(1)              // PCM
        file.writeShortLe(1)              // mono
        file.writeIntLe(sampleRate)
        file.writeIntLe(byteRate)
        file.writeShortLe(2)              // block align
        file.writeShortLe(16)             // bits per sample
        file.writeBytes("data"); file.writeIntLe(dataBytes.toInt())
    }

    private fun RandomAccessFile.writeIntLe(v: Int) {
        write(
            byteArrayOf(
                (v and 0xff).toByte(),
                (v shr 8 and 0xff).toByte(),
                (v shr 16 and 0xff).toByte(),
                (v shr 24 and 0xff).toByte()
            )
        )
    }

    private fun RandomAccessFile.writeShortLe(v: Int) {
        write(byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte()))
    }
}
