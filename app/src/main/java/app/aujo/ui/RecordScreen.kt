package app.aujo.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                .padding(pad),
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

            Spacer(Modifier.height(28.dp))
            LevelMeter(level)
            Text(
                if (isRecording) "Recording…" else "Tap the button to start",
                fontSize = 14.sp,
                color = InkSoft
            )

            Spacer(Modifier.weight(1f))

            RecordButton(
                isRecording = isRecording,
                onClick = {
                    if (!isRecording) vm.startRecording()
                    else vm.stopRecording { entry -> onSaved(entry?.id) }
                }
            )

            Spacer(Modifier.height(48.dp))
        }
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
            // White rounded square = stop
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Ink)
            )
        } else {
            // Red dot = record
            Box(
                Modifier
                    .size(62.dp)
                    .clip(CircleShape)
                    .background(RecordRed)
            )
        }
    }
}
