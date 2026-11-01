package app.aujo.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailScreen(
    entryId: Long,
    vm: JournalViewModel,
    onBack: () -> Unit
) {
    val entry by vm.observeEntry(entryId).collectAsState(initial = null)
    val current = entry

    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var positionSec by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragSec by remember { mutableFloatStateOf(0f) }
    var confirmDelete by remember { mutableStateOf(false) }

    // (Re)create the player whenever the entry's file changes.
    LaunchedEffect(current?.filePath) {
        val e = current ?: return@LaunchedEffect
        player?.release()
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
                playing = false
                positionSec = 0
            }
        }
        playing = false
        positionSec = 0
        player = p
    }

    // Position ticker while playing.
    LaunchedEffect(playing) {
        while (playing) {
            delay(200)
            player?.let { if (!dragging) positionSec = it.currentPosition / 1000 }
        }
    }

    // Release on leaving the screen.
    DisposableEffect(Unit) {
        onDispose {
            try { player?.release() } catch (_: Exception) {}
            player = null
        }
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

        val e = current
        val durationSec = (e.durationMs.coerceAtLeast(0)) / 1000

        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(24.dp))
            Text(formatDate(e.createdAt), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Ink)
            Text("${formatMs(e.durationMs)} • audio note", fontSize = 13.sp, color = InkSoft)

            Spacer(Modifier.height(32.dp))

            // Seek bar in seconds.
            Slider(
                value = when {
                    dragging -> dragSec
                    durationSec > 0 -> positionSec.toFloat()
                    else -> 0f
                },
                onValueChange = {
                    dragging = true
                    dragSec = it
                    if (durationSec > 0) positionSec = it.toInt()
                },
                onValueChangeFinished = {
                    dragging = false
                    player?.seekTo((dragSec.toInt()) * 1000)
                },
                valueRange = 0f..durationSec.coerceAtLeast(1).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = RecordRed,
                    activeTrackColor = RecordRed,
                    inactiveTrackColor = Chip
                )
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMs(if (dragging) (dragSec.toLong() * 1000) else positionSec.toLong() * 1000), fontSize = 12.sp, color = InkSoft, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Text(formatMs(e.durationMs), fontSize = 12.sp, color = InkSoft, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }

            Spacer(Modifier.height(24.dp))

            // Play / pause.
            Box(
                Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(RecordRed)
                    .clickable {
                        val p = player ?: return@clickable
                        if (playing) {
                            p.pause()
                            playing = false
                        } else {
                            p.start()
                            playing = true
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (playing) PauseGlyph() else PlayGlyph()
            }

            Spacer(Modifier.height(16.dp))
            Text(
                if (playing) "Playing…" else "Tap play to listen",
                fontSize = 13.sp,
                color = InkSoft
            )

            Spacer(Modifier.weight(1f))

            // Transcript placeholder until M3.
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(ChipSoft)
                    .padding(16.dp)
            ) {
                Column {
                    Text("Transcript", fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = InkSoft)
                    Text("Coming in M3 — on-device speech transcription.", fontSize = 13.sp, color = InkSoft)
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
    Canvas(Modifier.size(30.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
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
