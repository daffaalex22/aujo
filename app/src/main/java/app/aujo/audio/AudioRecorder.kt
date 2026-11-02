package app.aujo.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.NoiseSuppressor
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
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
 * Noise cleanup happens inside the capture session (M3.5): the platform
 * [NoiseSuppressor] is attached to the AudioRecord when available, so noise
 * already the phone's DSP removed before audio reaches this class. It is
 * therefore baked into the saved WAV as it is written.
 */
class AudioRecorder {

    /** Speech-recognition-friendly sample rate: no resample needed later. */
    val sampleRate = 16_000

    private val _level = MutableStateFlow(0f)

    /** Loudness normalized to 0..1, updated about every 100 ms while recording. */
    val level: StateFlow<Float> get() = _level

    /**
     * Fraction of this recording's frames that RNNoise considered voice-shaped
     * so far (0..1; 0 with no denoiser). Read after [stop] by the caller.
     */
    var voiceFraction: Float = 0f
        private set

    /** Human-readable noise-cleanup method in use for the current/last recording. */
    private val _noiseCleanup = MutableStateFlow("")
    val noiseCleanup: StateFlow<String> get() = _noiseCleanup

    @Volatile private var recordThread: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var pendingFile: RandomAccessFile? = null
    @Volatile private var framesWritten = 0L
    private var audioRecord: AudioRecord? = null
    private var suppressor: NoiseSuppressor? = null
    private var denoiserHandle: Long = 0L

    /**
     * Starts recording into [target] (a .wav path).
     * [denoiseMode]: -1 off, else one of [DenoiseJni.MODE_LIGHT] / [DenoiseJni.MODE_VOICE_ONLY].
     * Returns false if the microphone could not be initialized.
     */
    @SuppressLint("MissingPermission") // caller is responsible for RECORD_AUDIO
    fun start(target: File, denoiseMode: Int = -1): Boolean {
        if (recordThread != null) return false

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false

        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 4
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }

        attachNoiseCleanup(rec)

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
        voiceFraction = 0f
        audioRecord = rec
        attachDenoiser(denoiseMode)
        rec.startRecording()
        attachNoiseCleanup(rec) // after routing starts: some stacks reject effects on idle sessions
        recordThread = thread(name = "aujo-recorder") { readLoop(rec, file, denoiserHandle) }
        return true
    }

    /**
     * Creates the native RNNoise denoiser for this recording when requested.
     * Fail-soft: if the engine can't initialize we record DSP-cleaned (or raw)
     * audio and say so rather than losing the capture.
     */
    private fun attachDenoiser(mode: Int) {
        denoiserHandle = 0L
        _noiseCleanup.value = ""
        if (mode < 0) return
        val h = DenoiseJni.nativeCreate(sampleRate, mode)
        if (h != 0L) {
            denoiserHandle = h
            _noiseCleanup.value = if (mode == DenoiseJni.MODE_VOICE_ONLY) "neural cleanup: voice-only"
            else "neural cleanup: light"
        } else {
            Log.w("Aujo", "native denoiser init failed; recording without neural cleanup")
            _noiseCleanup.value = "neural cleanup: failed to start"
        }
    }

    /**
     * Attaches the platform speakerphone/noise effects to the capture session.
     * These run in the vendor DSP before we ever see the samples, so the WAV
     * written to disk is the post-suppression signal. Fail-soft: a ROM without
     * the effect simply records raw, and the UI states that honestly.
     */
    private fun attachNoiseCleanup(rec: AudioRecord) {
        suppressor?.release()
        suppressor = null
        var dspNote = ""
        try {
            if (NoiseSuppressor.isAvailable()) {
                val ns = NoiseSuppressor.create(rec.audioSessionId)
                if (ns != null && ns.enabled) {
                    suppressor = ns
                    dspNote = "DSP"
                } else {
                    runCatching { ns?.release() }
                    dspNote = ""
                }
            }
        } catch (e: Exception) {
            Log.w("Aujo", "NoiseSuppressor attach failed", e)
            dspNote = ""
        }
        // Combine with whatever the neural layer reported (order: neural ran first).
        val neural = _noiseCleanup.value
        _noiseCleanup.value = when {
            dspNote.isNotEmpty() && neural.isNotEmpty() -> "$neural + $dspNote"
            dspNote.isNotEmpty() -> "noise cleanup: on-device $dspNote"
            neural.isNotEmpty() -> neural
            else -> "noise cleanup: off (not available on this phone)"
        }
    }

    private fun readLoop(rec: AudioRecord, file: RandomAccessFile, denoiser: Long) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val chunk = ShortArray(sampleRate / 10) // 100 ms of audio
        val bytes = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)

        while (!stopRequested) {
            val n = rec.read(chunk, 0, chunk.size)
            if (n <= 0) continue

            val processed: ShortArray = if (denoiser != 0L && n == chunk.size) {
                runCatching { DenoiseJni.nativeProcess(denoiser, chunk) }
                    .onFailure { Log.w("Aujo", "denoise chunk failed", it) }
                    .getOrNull() ?: chunk // process failed: write the raw audio, honestly note it below
            } else chunk

            bytes.clear()
            for (i in 0 until n) bytes.putShort(processed[i])
            file.write(bytes.array(), 0, n * 2)
            framesWritten += n
            _level.value = normalizedLevel(processed, n)
        }
        _level.value = 0f
        if (denoiser != 0L) {
            runCatching {
                val stats = DenoiseJni.nativeStats(denoiser)
                if (stats[0] > 0) voiceFraction = stats[1].toFloat() / stats[0]
            }
        } else {
            voiceFraction = -1f // no denoiser: unknown
        }
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

        suppressor?.let { s -> runCatching { s.release() } }
        suppressor = null

        if (denoiserHandle != 0L) {
            runCatching { DenoiseJni.nativeDestroy(denoiserHandle) }
            denoiserHandle = 0L
        }

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
