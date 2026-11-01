package app.aujo.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aujo.audio.TranscriberStatus
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    vm: JournalViewModel,
    onSaved: (Long?) -> Unit,
    onBack: () -> Unit
) {
    val isRecording by vm.isRecording.collectAsState()
    val level by vm.level.collectAsState()
    val startedAt by vm.startedAt.collectAsState()
    val committed by vm.transcriptText.collectAsState()
    val partial by vm.transcriptPartial.collectAsState()
    val status by vm.transcriberStatus.collectAsState()
    val activeLang by vm.activeLang.collectAsState()

    // Ticks while recording so the timer string refreshes.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isRecording) {
        while (isRecording) {
            delay(200)
            now = System.currentTimeMillis()
        }
    }
    val elapsed = if (isRecording) now - startedAt else 0L

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("Record", color = Ink, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isRecording) {
                            vm.stopRecording { onSaved(it?.id) }
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Ink)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))

            Text(
                formatMs(elapsed),
                fontSize = 52.sp,
                color = Ink,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(16.dp))
            LevelMeter(level)

            Spacer(Modifier.height(16.dp))

            // Live transcript panel.
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1.6f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ChipSoft)
            ) {
                TranscribePanel(
                    committed = committed,
                    partial = partial,
                    isRecording = isRecording
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(bottom = 8.dp, start = 14.dp)
                ) {
                    Text(statusLine(status, isRecording), fontSize = 11.sp, color = InkSoft)
                    if (status == TranscriberStatus.FAILURE || status == TranscriberStatus.RESTARTING || status == TranscriberStatus.LISTENING) {
                        val note by vm.transcribeNote.collectAsState()
                        if (note.isNotBlank() && committed.isBlank() && partial.isBlank()) {
                            Text(note, fontSize = 10.sp, color = InkSoft)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Language selector — locked while recording.
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                LanguagePill("English", "en-US", activeLang == "en-US", !isRecording) {
                    vm.setLanguage("en-US")
                }
                Spacer(Modifier.size(10.dp))
                LanguagePill("Bahasa", "id-ID", activeLang == "id-ID", !isRecording) {
                    vm.setLanguage("id-ID")
                }
            }

            Spacer(Modifier.weight(1f))

            RecordButton(
                isRecording = isRecording,
                onClick = {
                    if (!isRecording) vm.startRecording()
                    else vm.stopRecording { entry -> onSaved(entry?.id) }
                }
            )

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun TranscribePanel(committed: String, partial: String, isRecording: Boolean) {
    val scroll = rememberScrollState()
    LaunchedEffect(committed, partial) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    Box(Modifier.fillMaxSize()) {
        if (committed.isBlank() && partial.isBlank()) {
            Text(
                if (isRecording) "Say something — your words appear here…" else "Your transcript appears here while you record.",
                fontSize = 13.sp,
                color = InkSoft,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 16.dp)
            )
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(vertical = 12.dp, horizontal = 16.dp)
            ) {
                Text(committed, fontSize = 14.sp, color = Ink, lineHeight = 20.sp)
                if (partial.isNotBlank()) {
                    Text("$partial…", fontSize = 14.sp, color = InkSoft, lineHeight = 20.sp)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private fun statusLine(status: TranscriberStatus, isRecording: Boolean): String = when {
    !isRecording -> ""
    status == TranscriberStatus.UNAVAILABLE -> "Speech engine unavailable on this device"
    status == TranscriberStatus.FAILURE -> "Live engine blocked by recorder — transcript will run after saving"
    status == TranscriberStatus.STARTING -> "Starting speech engine…"
    status == TranscriberStatus.RESTARTING -> "Listening…"
    else -> "Listening (on-device)…"
}

@Composable
private fun LanguagePill(
    label: String,
    lang: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) RecordRed else Chip)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) Ink else InkSoft,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
private fun LevelMeter(raw: Float) {
    val level by animateFloatAsState(
        targetValue = raw,
        animationSpec = spring(stiffness = 1200f),
        label = "level"
    )
    Box(
        Modifier
            .fillMaxWidth(0.65f)
            .height(10.dp)
            .background(ChipSoft, RoundedCornerShape(50))
    ) {
        if (level > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(if (level < 0.02f) 0.02f else level)
                    .fillMaxHeight()
                    .background(RecordRed, RoundedCornerShape(50))
            )
        }
    }
}

@Composable
private fun RecordButton(isRecording: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(104.dp)
            .clip(CircleShape)
            .background(Bg, CircleShape)
            .border(4.dp, RecordRed, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isRecording) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Ink)
            )
        } else {
            Box(
                Modifier
                    .size(62.dp)
                    .clip(CircleShape)
                    .background(RecordRed)
            )
        }
    }
}
