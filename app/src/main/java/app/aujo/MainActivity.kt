package app.aujo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.aujo.ui.Bg
import app.aujo.ui.Chip
import app.aujo.ui.Ink
import app.aujo.ui.InkSoft
import app.aujo.ui.JournalListScreen
import app.aujo.ui.JournalViewModel
import app.aujo.ui.EntryDetailScreen
import app.aujo.ui.RecordScreen
import app.aujo.ui.RecordRed

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AujoRoot()
        }
    }
}

@Composable
fun AujoRoot(vm: JournalViewModel = viewModel()) {
    val context = LocalContext.current
    val hasPermission = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    if (!hasPermission.value) {
        PermissionScreen(onAllow = {
            hasPermission.value = true
        })
    } else {
        AujoNavHost(vm)
    }
}

@Composable
private fun PermissionScreen(onAllow: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onAllow()
        // If denied, we simply stay on this screen; the user can tap Again.
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Aujo", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 40.sp, color = Ink)
        Spacer(Modifier.height(16.dp))
        Text(
            "Aujo needs microphone access to record your journals.\n" +
                "Audio never leaves your phone.",
            color = InkSoft,
            fontSize = 15.sp
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    onAllow()
                } else {
                    launcher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RecordRed, contentColor = Color.White)
        ) {
            Text("Allow microphone access")
        }
    }
}

@Composable
private fun AujoNavHost(vm: JournalViewModel) {
    val nav = rememberNavController()

    NavHost(nav, startDestination = "list") {
        composable("list") {
            val entries by vm.entries.collectAsState()
            JournalListScreen(
                entries = entries,
                onRecordClick = { nav.navigate("record") },
                onEntryClick = { id -> nav.navigate("entry/$id") }
            )
        }
        composable("record") {
            RecordScreen(
                vm = vm,
                onSaved = { id ->
                    if (id != null) {
                        nav.navigate("entry/$id") { popUpTo("record") { inclusive = true } }
                    } else {
                        nav.popBackStack()
                    }
                },
                onBack = { nav.popBackStack() }
            )
        }
        composable("entry/{id}") { backStack ->
            val id = backStack.arguments?.getString("id")?.toLongOrNull() ?: 0L
            EntryDetailScreen(
                entryId = id,
                vm = vm,
                onBack = { nav.popBackStack() }
            )
        }
    }
}
