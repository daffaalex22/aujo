package app.aujo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aujo.audio.SilenceAnalyzer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailScreen(
    entryId: Long,
    vm: JournalViewModel,
    onBack: () -> Unit
) {
    val entry by vm.observeEntry(entryId).collectAsState(initial = null)
    val playing by vm.playing.collectAsState()
    val positionSec by vm.positionSec.collectAsState()
    val skipSilence by vm.skipSilence.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    val current = entry
    var loadedId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(current?.id, current?.silenceJson) {
        if (current != null && loadedId != current.id) {
            vm.openEntry(current)
            loadedId = current.id
        } else if (current != null && current.silenceJson != null) {
            vm.openEntry(current) // refreshes the silence map, no-op otherwise
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { vm.closePlayer() }
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("Entry", color = Ink, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Ink)
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, "Delete entry", tint = InkSoft)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
            )
        }
    ) { pad ->
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("Loading…", color = InkSoft)
            }
            return@Scaffold
        }

        val e = current!!

        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(24.dp))
            Text(formatDate(e.createdAt), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text("${formatMs(e.durationMs)} • audio note", fontSize = 13.sp, color = InkSoft)

            Spacer(Modifier.height(28.dp))

            // Waveform + skip behavior. While analysis runs, show a static bar.
            val levels = SilenceAnalyzer.decodeLevels(e.waveformJson)
            val silences = SilenceAnalyzer.decodeSilences(e.silenceJson)
            val positionMs = (positionSec.toLong() * 1000).coerceAtMost(e.durationMs)

            if (levels.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ChipSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (e.silenceJson == null) "Analyzing audio…" else "Waveform unavailable",
                        fontSize = 13.sp, color = InkSoft
                    )
                }
            } else {
                WaveformSeek(
                    levels = levels,
                    silences = silences,
                    durationMs = e.durationMs,
                    positionMs = positionMs,
                    onSeek = { ms -> vm.seekToSec((ms / 1000).toInt()) }
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatMs(positionMs),
                    fontSize = 12.sp, color = InkSoft, fontFamily = FontFamily.Monospace
                )
                Text(
                    formatMs(e.durationMs),
                    fontSize = 12.sp, color = InkSoft, fontFamily = FontFamily.Monospace
                )
            }

            Spacer(Modifier.height(12.dp))

            // Skip-silence toggle.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = skipSilence,
                    onCheckedChange = { vm.setSkipSilence(it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = RecordRed,
                        checkedThumbColor = Ink,
                        uncheckedTrackColor = Chip,
                        uncheckedThumbColor = InkSoft
                    )
                )
                Spacer(Modifier.size(8.dp))
                Column {
                    Text("Skip silence", fontSize = 14.sp, color = Ink)
                    Text(
                        if (silences.isEmpty() && e.silenceJson != null) "No silence detected"
                        else if (silences.isNotEmpty()) "${silences.size} quiet stretch${if (silences.size == 1) "" else "es"} — playback jumps over them"
                        else "One-time analysis in progress",
                        fontSize = 11.sp, color = InkSoft
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Play / pause.
            Box(
                Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(RecordRed)
                    .clickable { vm.togglePlay() },
                contentAlignment = Alignment.Center
            ) {
                if (playing) PauseGlyph() else PlayGlyph()
            }

            Spacer(Modifier.height(12.dp))
            Text(if (playing) "Playing…" else "Tap play to listen", fontSize = 13.sp, color = InkSoft)

            Spacer(Modifier.weight(1f))

            // Transcript (live entries carry it; older ones can be filled by M5).
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(ChipSoft)
                    .padding(16.dp)
            ) {
                Column {
                    Text("Transcript", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = InkSoft)
                    Spacer(Modifier.height(6.dp))
                    var expanded by remember { mutableStateOf(false) }
                    val note by vm.transcribeNote.collectAsState()
                    val text = e.transcript?.trim().orEmpty()
                    if (text.isBlank()) {
                        Text(
                            "No transcript for this entry. Stored audio is safe — Whisper (M5) can transcribe it later.",
                            fontSize = 13.sp, color = InkSoft, lineHeight = 18.sp
                        )
                        if (note.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(note, fontSize = 11.sp, color = Ok, lineHeight = 15.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = { vm.retryTranscribe(e) }) {
                            Text("Try transcribing again", color = RecordRed, fontSize = 13.sp)
                        }
                    } else {
                        Text(
                            text,
                            fontSize = 14.sp, color = Ink, lineHeight = 20.sp,
                            maxLines = if (expanded) Int.MAX_VALUE else 5,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clickable { expanded = !expanded }
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this entry?", color = Ink) },
            text = { Text("The audio will be removed permanently.", color = InkSoft) },
            containerColor = Chip,
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    current?.let { vm.delete(it) }
                    onBack()
                }) { Text("Delete", color = RecordRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = Ink) }
            }
        )
    }
}

@Composable
private fun PlayGlyph() {
    androidx.compose.foundation.Canvas(Modifier.size(30.dp)) {
        val w = size.width
        val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.15f, 0f)
            lineTo(w * 0.95f, h * 0.5f)
            lineTo(w * 0.15f, h)
            close()
        }
        drawPath(path, color = Ink)
    }
}

@Composable
private fun PauseGlyph() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(width = 9.dp, height = 28.dp).background(Ink, RoundedCornerShape(2.dp)))
        Box(Modifier.size(width = 9.dp, height = 28.dp).background(Ink, RoundedCornerShape(2.dp)))
    }
}
