package name.onetrue.supertuxrower.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import name.onetrue.supertuxrower.model.Band

val Ice = Color(0xFFDDF3FB)
val Ink = Color(0xFFF0EFEC)
val InkMuted = Color(0xFFB8C4CC)
val Deep = Color(0xFF06142B)
val PanelColor = Color(0xFF101C2E)
val Accent = Color(0xFFFAAA1E)

/** Cool to hot, the same in every mode. */
fun Band.color(): Color = when (this) {
    Band.REST -> Color(0xFF7891A6)
    Band.UT2 -> Color(0xFF4FC3F7)
    Band.UT1 -> Color(0xFF26C6A6)
    Band.AT -> Color(0xFFFFD54F)
    Band.TR -> Color(0xFFFF9800)
    Band.AN -> Color(0xFFFF5252)
}

/** Numbers that update while rowing: tabular figures so they don't jitter. */
fun numbers(size: Int, weight: FontWeight = FontWeight.SemiBold, color: Color = Ink) =
    TextStyle(fontSize = size.sp, fontWeight = weight, color = color, fontFeatureSettings = "tnum")

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.Black,
            background = Deep,
            surface = PanelColor,
            onSurface = Ink,
            onBackground = Ink,
        ),
        content = content,
    )
}

/** Marks screens driven by the simulated rower, so a test run is never mistaken for real rowing. */
@Composable
fun SimulatedBadge(modifier: Modifier = Modifier) {
    Text(
        "SIMULATED ROWER",
        modifier.background(Color(0xFF5A2A6E), RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
    )
}
