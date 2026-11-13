package name.onetrue.supertuxrower.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import name.onetrue.supertuxrower.R

/** The start-up screen: the same picture as the system's launch background, plus the rower's status. */
@Composable
fun LoadingScreen(status: String) {
    Box(Modifier.fillMaxSize()) {
        Image(
            painterResource(R.drawable.ic_launcher_background),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Image(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(360.dp).align(Alignment.Center),
        )
        Column(
            Modifier.align(Alignment.Center).offset(y = 170.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "SuperTuxRow",
                style = TextStyle(
                    fontSize = 64.sp, fontWeight = FontWeight.Bold, color = Ink,
                    shadow = Shadow(color = Deep, blurRadius = 12f),
                ),
            )
            Text(status, style = MaterialTheme.typography.titleMedium, color = InkMuted)
        }
    }
}
