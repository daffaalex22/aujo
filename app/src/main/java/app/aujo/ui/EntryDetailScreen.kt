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
import app.aujo.audio.WhisperModelManager
import app.aujo.data.EntryEntity
import app.aujo.data.TranscriptState
import kotlinx.coroutines.delay

@Composable
private fun DetailTranscriptBody(e: EntryEntity, vm: JournalViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val modelState by vm.whisperModelState.collectAsState()
    val transcribingEntryId by vm.transcribingEntryId.collectAsState()
    val text = e.transcript?.trim().orEmpty()

    when {
        // Final transcript on screen (either source).
        text.isNotBlank() -> {
            Text(
                text,
                fontSize = 14.sp, color = Ink, lineHeight = 20.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 5,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded }
            )
            Spacer(Modifier.height(4.dp))
            val caption = when (e.transcriptSource) {
                "live" -> "captured live while recording"
                "whisper" -> "transcribed on this phone — offline"
                else -> null
            }
            if (caption != null) {
                Text(caption, fontSize = 10.sp, color = Ok)
            }
            TextButton(
                onClick = { if (transcribingEntryId == null) vm.retryTranscribe(e) },
                enabled = transcribingEntryId == null
            ) {
                Text("Transcribe again", fontSize = 12.sp, color = InkSoft)
            }
        }
        e.transcriptState == TranscriptState.QUEUED ->
            Text("In queue — transcriptions run one at a time…", fontSize = 13.sp, color = InkSoft)
        e.transcriptState == TranscriptState.RUNNING || transcribingEntryId == e.id ->
            RunningGlyph("Transcribing offline… this can take a while")
        e.transcriptState == TranscriptState.FAILED -> {
            Text(
                e.transcriptError ?: "Transcription failed",
                fontSize = 13.sp, color = RecordRed, lineHeight = 18.sp
            )
            TextButton(onClick = { if (transcribingEntryId == null) vm.retryTranscribe(e) }) {
                Text("Retry", fontSize = 13.sp, color = Ink)
            }
        }
        else -> when (modelState.status) {
            WhisperModelManager.Status.READY ->
                TextButton(
                    onClick = { if (transcribingEntryId == null) vm.retryTranscribe(e) },
                    enabled = transcribingEntryId == null
                ) { Text("Transcribe this entry", fontSize = 13.sp, color = RecordRed) }
            WhisperModelManager.Status.DOWNLOADING -> {
                Text(
                    "Downloading model… ${(modelState.downloadedFraction * 100).toInt()}% of 31 MB",
                    fontSize = 13.sp, color = InkSoft
                )
            }
            WhisperModelManager.Status.ERROR -> {
                Text("Model download failed: ${modelState.message}", fontSize = 13.sp, color = RecordRed)
                TextButton(onClick = { vm.downloadWhisperModel() }) {
                    Text("Try again", fontSize = 13.sp, color = Ink)
                }
            }
            WhisperModelManager.Status.NOT_DOWNLOADED -> {
                Text(
                    "Transcribe with a 31 MB one-time download of an offline model (English + Bahasa). " +
                        "It runs entirely on this phone — your audio never leaves it. " +
                        "The model is stored in app-private storage.",
                    fontSize = 13.sp, color = InkSoft, lineHeight = 18.sp
                )
                TextButton(onClick = { vm.downloadWhisperModel() }) {
                    Text("Download model", fontSize = 13.sp, color = RecordRed)
                }
            }
        }
    }
}

@Composable
private fun RunningGlyph(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        var ticks by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) { delay(400); ticks = (ticks + 1) % 4 }
        }
        Text(label.plus(".".repeat(ticks)), fontSize = 13.sp, color = InkSoft)
    }
}

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

            // Transcript (filled live by the built-in engine where it works,
            // or by the on-device Whisper model after saving).

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
                    DetailTranscriptBody(e, vm)
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
