package app.aujo.audio

import android.util.Log
import org.json.JSONArray
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Turns a recorded WAV into a silence map and a loudness waveform.
 *
 * Runs as a one-pass post-recording job (not during recording), so it can
 * first estimate the room's noise floor and only then decide where speech
 * begins and ends. Hard-coded to the format our recorder produces
 * (16 kHz, mono, 16-bit) but reads the header for robustness.
 *
 * Timeline granularity: 100 ms frames.
 */
object SilenceAnalyzer {

    /** Bump when tuning changes results so stored maps get re-analyzed. */
    const val ANALYSIS_VERSION = 2

    private const val FRAME_MS = 100L

    /** dB above the estimated noise floor that counts as "speech".
     *  Conservative: a low margin is far less likely to swallow quiet
     *  speech in noisy rooms, at the cost of marking more ambience as speech. */
    private const val SPEECH_ABOVE_NOISE_DB = 8.0

    /** A speech burst shorter than this is treated as noise (e.g. a knock). */
    private const val MIN_SPEECH_MS = 700L

    /** A quiet stretch shorter than this is not worth skipping. */
    private const val MIN_SILENCE_MS = 2000L

    /** Shrink every detected silence by this on each side so playback never
     *  clips the onset or tail of a word. */
    private const val EDGE_GUARD_MS = 300L

    /** After applying the guard, a remaining stretch below this is dropped. */
    private const val MIN_SKIP_MS = 1200L

    /** Level normalization window, matching AudioRecorder's meter. */
    private const val LEVEL_DB_WINDOW = 50.0

    data class Result(
        /** Loudness per frame, 0..1 (100 ms per frame). */
        val levels: List<Float>,
        /** Silent stretches, sorted, non-overlapping: (startMs, endMsExclusive). */
        val silences: List<Pair<Long, Long>>
    )

    fun analyze(file: File): Result {
        val bytes = file.readBytes()
        val sampleRate = intAt(bytes, 24)
        val dataSize = intAt(bytes, 40)
        val dataStart = 44
        if (sampleRate <= 0 || dataSize <= 0) return Result(emptyList(), emptyList())

        val bytesPerFrame = (sampleRate * 2L * FRAME_MS / 1000L).toInt() // mono, 16-bit
        val frameCount = (dataSize / bytesPerFrame).coerceAtLeast(0)
        val usable = minOf(dataSize, bytes.size - dataStart)

        // RMS -> dBFS per frame.
        val db = FloatArray(frameCount)
        val levels = ArrayList<Float>(frameCount)
        var offset = dataStart
        for (f in 0 until frameCount) {
            val frameBytes = minOf(bytesPerFrame, usable - offset)
            if (frameBytes <= 0) { db[f] = -100f; levels.add(0f); continue }
            var sum = 0.0
            var n = 0
            for (i in 0 until frameBytes / 2) {
                val lo = bytes[offset + i * 2].toInt() and 0xff
                val hi = bytes[offset + i * 2 + 1].toInt()
                val s = (hi shl 8) or lo
                sum += s.toDouble() * s
                n++
            }
            val rms = if (n > 0) sqrt(sum / n) else 0.0
            val frameDb = if (rms < 1.0) -100.0 else 20 * log10(rms / 32_767.0)
            db[f] = frameDb.toFloat()
            levels.add(((frameDb + LEVEL_DB_WINDOW) / LEVEL_DB_WINDOW).coerceIn(0.0, 1.0).toFloat())
            offset += frameBytes
        }

        // Noise floor = 10th percentile; speech line sits above it.
        val sorted = db.clone()
        sorted.sort()
        val noiseFloor = if (sorted.isEmpty()) -100.0 else sorted[(sorted.size * 0.1).toInt()].toDouble()
        val speechThreshold = noiseFloor + SPEECH_ABOVE_NOISE_DB

        val speech = BooleanArray(frameCount) { db[it].toDouble() > speechThreshold }

        // Smooth: drop tiny speech blips, then bridge tiny gaps...
        eraseShortRuns(speech, runMs = MIN_SPEECH_MS)
        fillShortGaps(speech, gapMs = MIN_SILENCE_MS)

        // ...and everything still non-speech is a silence segment
        // (shrunk inward by the guard so word edges are never clipped).
        val silences = ArrayList<Pair<Long, Long>>()
        var i = 0
        while (i < frameCount) {
            if (!speech[i]) {
                val start = i
                while (i < frameCount && !speech[i]) i++
                val rawStart = start * FRAME_MS
                val rawEnd = i * FRAME_MS
                val guardedStart = rawStart + EDGE_GUARD_MS
                val guardedEnd = rawEnd - EDGE_GUARD_MS
                if (guardedEnd - guardedStart >= MIN_SKIP_MS) {
                    silences.add(guardedStart to guardedEnd)
                }
            } else {
                i++
            }
        }

        Log.d(
            "AujoAnalyzer",
            "analyze ${file.name}: frames=$frameCount noiseFloor=${"%.1f".format(noiseFloor)} " +
                "threshold=${"%.1f".format(speechThreshold)} silences=${silences.size}"
        )
        return Result(levels, silences)
    }

    /** Speech runs shorter than [runMs] become non-speech. */
    private fun eraseShortRuns(speech: BooleanArray, runMs: Long) {
        val runFrames = (runMs / FRAME_MS).toInt()
        var i = 0
        while (i < speech.size) {
            if (speech[i]) {
                val start = i
                while (i < speech.size && speech[i]) i++
                if (i - start < runFrames) {
                    for (j in start until i) speech[j] = false
                }
            } else {
                i++
            }
        }
    }

    /** Non-speech gaps shorter than [gapMs] become speech (not worth skipping). */
    private fun fillShortGaps(speech: BooleanArray, gapMs: Long) {
        val gapFrames = (gapMs / FRAME_MS).toInt()
        var i = 0
        while (i < speech.size) {
            if (!speech[i]) {
                val start = i
                while (i < speech.size && !speech[i]) i++
                val end = i
                val before = start > 0 && speech[start - 1]
                val after = end < speech.size && speech[end]
                if (before && after && end - start < gapFrames) {
                    for (j in start until end) speech[j] = true
                }
            } else {
                i++
            }
        }
    }

    private fun intAt(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xff) or
            ((b[i + 1].toInt() and 0xff) shl 8) or
            ((b[i + 2].toInt() and 0xff) shl 16) or
            ((b[i + 3].toInt() and 0xff) shl 24)

    // ---- JSON codecs (stored in the `entries` table) ----------------------

    /** [[start,end],[start,end],...] with ms longs. */
    fun encodeSilences(silences: List<Pair<Long, Long>>): String {
        val arr = JSONArray()
        silences.forEach { (s, e) ->
            val pair = JSONArray()
            pair.put(s); pair.put(e)
            arr.put(pair)
        }
        return arr.toString()
    }

    fun encodeLevels(levels: List<Float>): String {
        val arr = JSONArray()
        levels.forEach { arr.put((it * 100).toInt() / 100.0) }
        return arr.toString()
    }

    fun decodeSilences(json: String?): List<Pair<Long, Long>> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val pair = arr.getJSONArray(it)
                pair.getLong(0) to pair.getLong(1)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun decodeLevels(json: String?): List<Float> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.optDouble(it, 0.0).toFloat() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
