package app.aujo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AujoApp()
        }
    }
}

private val Bg = Color(0xFF101014)
private val Ink = Color(0xFFF2F1EA)
private val InkSoft = Color(0xFF9A9AA2)
private val Chip = Color(0xFF24242C)
private val Ok = Color(0xFF8BC48A)

@Composable
fun AujoApp() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Aujo",
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                color = Ink
            )
            Text(
                text = "Private audio journal",
                fontSize = 15.sp,
                color = InkSoft
            )
            Box(
                modifier = Modifier
                    .padding(top = 32.dp)
                    .background(Chip, RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "M0 — scaffold OK",
                    fontSize = 13.sp,
                    color = Ok,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
