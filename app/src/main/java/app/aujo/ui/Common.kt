package app.aujo.ui

import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Shared dark palette for all screens.
val Bg = Color(0xFF101014)
val Ink = Color(0xFFF2F1EA)
val InkSoft = Color(0xFF9A9AA2)
val Chip = Color(0xFF24242C)
val ChipSoft = Color(0xFF1B1B21)
val Ok = Color(0xFF8BC48A)
val RecordRed = Color(0xFFE5484D)

/** 63400 -> "1:04" */
fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/** 1730000000000 -> "Sun, 2 Nov 2025 • 06:12" (device locale). */
fun formatDate(ts: Long): String =
    SimpleDateFormat("EEE, d MMM yyyy • HH:mm", Locale.getDefault()).format(Date(ts))
