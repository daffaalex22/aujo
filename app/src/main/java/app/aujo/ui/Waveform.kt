package app.aujo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

private val SpeechPlayed = RecordRed
private val SpeechUnplayed = Color(0xFF6E3538)
private val SilencePlayed = Color(0xFF3A3A44)
private val SilenceUnplayed = Color(0xFF26262D)
private val Playhead = Ink

/**
 * Seekable waveform. Each bar is a fixed slice of the timeline; its height
 * is the loudness of the matching 100 ms frame. Silences render as short,
 * flat, dimmed bars; the played portion is brighter; a playhead marks the
 * live position. Tapping seeks.
 */
@Composable
fun WaveformSeek(
    levels: List<Float>,
    silences: List<Pair<Long, Long>>,
    durationMs: Long,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (durationMs > 0) {
                        onSeek(((offset.x / size.width) * durationMs).toLong())
                            .also { }
                    }
                }
            }
    ) {
        val barCount = 56
        if (durationMs > 0 && size.width > 0) {
            val step = size.width / barCount
            val barWidth = (step * 0.55f).coerceIn(2f, 24f)
            val minH = 4.dp.toPx()
            val corner = CornerRadius(barWidth / 2f, barWidth / 2f)

            for (i in 0 until barCount) {
                val sliceStart = i * durationMs / barCount
                val sliceEnd = (i + 1) * durationMs / barCount
                val mid = (sliceStart + sliceEnd) / 2
                val inSilence = silences.any { mid >= it.first && mid < it.second }
                val level = if (levels.isEmpty()) 0f
                else levels[((i.toDouble() / barCount) * levels.size).toInt()
                    .coerceIn(0, levels.size - 1)]
                val h = (minH + level * (size.height - minH)).coerceAtMost(size.height)
                val played = mid <= positionMs
                val color = when {
                    inSilence && played -> SilencePlayed
                    inSilence -> SilenceUnplayed
                    played -> SpeechPlayed
                    else -> SpeechUnplayed
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(i * step, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = corner
                )
            }

            if (positionMs in 0..durationMs) {
                val px = positionMs.toFloat() / durationMs * size.width
                drawLine(Playhead, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2.dp.toPx())
            }
        }
    }
}
