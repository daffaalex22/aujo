package app.aujo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aujo.data.EntryEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalListScreen(
    entries: List<EntryEntity>,
    onRecordClick: () -> Unit,
    onEntryClick: (Long) -> Unit
) {
    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Aujo",
                        color = Ink,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onRecordClick,
                containerColor = RecordRed,
                contentColor = Ink
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Record a new entry")
            }
        }
    ) { pad ->
        if (entries.isEmpty()) {
            EmptyState(pad)
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    EntryCard(entry) { onEntryClick(entry.id) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryCard(entry: EntryEntity, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Chip, contentColor = Ink)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            WaveformGlyph()
            Column(Modifier.padding(start = 14.dp)) {
                Text(
                    formatDate(entry.createdAt),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink
                )
                Text(
                    "${formatMs(entry.durationMs)} • audio note",
                    fontSize = 13.sp,
                    color = InkSoft
                )
                val snippet = entry.transcript?.trim()
                if (!snippet.isNullOrBlank()) {
                    Text(
                        snippet,
                        fontSize = 12.sp,
                        color = InkSoft,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun WaveformGlyph() {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(4.dp, 14.dp).background(InkSoft, RoundedCornerShape(2.dp)))
        Box(Modifier.size(4.dp, 24.dp).background(Ink, RoundedCornerShape(2.dp)))
        Box(Modifier.size(4.dp, 18.dp).background(InkSoft, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun EmptyState(pad: PaddingValues) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(pad),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("No entries yet", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Tap + to record your first journal",
            color = InkSoft,
            fontSize = 14.sp
        )
    }
}
