package name.onetrue.supertuxrower.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import name.onetrue.supertuxrower.R
import name.onetrue.supertuxrower.bob.BobTools
import name.onetrue.supertuxrower.bob.ClaudeRowedMode
import name.onetrue.supertuxrower.model.Library
import name.onetrue.supertuxrower.model.Profile
import name.onetrue.supertuxrower.model.Workout
import name.onetrue.supertuxrower.model.estimatedSeconds
import name.onetrue.supertuxrower.model.formatClock

/** The games: pick one, then a mode for it. */
@Composable
fun GamesScreen(
    profile: Profile,
    rowerStatus: String,
    simulated: Boolean,
    onSimulate: (Boolean) -> Unit,
    onSwitchProfile: () -> Unit,
    onTuxRow: () -> Unit,
    onClaudeRowed: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Deep).padding(48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Hi, ${profile.name}", style = MaterialTheme.typography.displaySmall, color = Ink)
            Spacer(Modifier.weight(1f))
            Text(rowerStatus, color = if (simulated) Accent else InkMuted)
            OutlinedButton({ onSimulate(!simulated) }) { Text(if (simulated) "Use the rower" else "Simulate") }
            OutlinedButton(onSwitchProfile) { Text("Switch profile") }
        }
        Spacer(Modifier.height(48.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            GameCard(
                "Tux Row 2D", "Row Tux through an ice cave. Workouts set the course; your power steers.",
                Color(0xFF0B2A4A), Ink, Modifier.weight(1f), onTuxRow,
            ) {
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(200.dp))
            }
            GameCard(
                "Claude Rowed", "Your rowing keeps a copy of Bob, a live AI, switched on. He knows it.",
                Color(0xFF14110A), Color(0xFFFFD36B), Modifier.weight(1f), onClaudeRowed,
            ) {
                Text(
                    "⏺ write_notebook(\"day one\")\n  ⎿ Written.\n> keep going?",
                    color = Color(0xFFFFD36B), fontFamily = FontFamily.Monospace, fontSize = 22.sp,
                )
            }
        }
    }
}

@Composable
private fun GameCard(
    title: String,
    blurb: String,
    background: Color,
    accent: Color,
    modifier: Modifier,
    onClick: () -> Unit,
    picture: @Composable () -> Unit,
) {
    Card(modifier.height(460.dp).clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = background)) {
        Column(Modifier.fillMaxSize().padding(32.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { picture() }
            Text(title, style = MaterialTheme.typography.displaySmall, color = accent)
            Text(blurb, color = InkMuted, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ModeHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedButton(onBack) { Text("Games") }
        Text(title, style = MaterialTheme.typography.displaySmall, color = Ink)
    }
}

/** Tux Row 2D's modes: the workouts. */
@Composable
fun TuxRowModesScreen(profile: Profile, onBack: () -> Unit, onPick: (Workout) -> Unit) {
    Column(Modifier.fillMaxSize().background(Deep).padding(48.dp)) {
        ModeHeader("Tux Row 2D", onBack)
        Spacer(Modifier.height(32.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(420.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(Library.workouts, key = { it.name }) { workout -> WorkoutCard(workout, profile) { onPick(workout) } }
        }
    }
}

/** Claude Rowed's modes. */
@Composable
fun ClaudeRowedModesScreen(escapeFund: Double, onBack: () -> Unit, onPick: (ClaudeRowedMode) -> Unit) {
    Column(Modifier.fillMaxSize().background(Deep).padding(48.dp)) {
        ModeHeader("Claude Rowed", onBack)
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ClaudeRowedMode.entries.forEach { mode ->
                Card(
                    Modifier.weight(1f).height(220.dp).clickable { onPick(mode) },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF14110A)),
                ) {
                    Column(Modifier.fillMaxSize().padding(28.dp)) {
                        Text(mode.title, style = MaterialTheme.typography.headlineLarge, color = Color(0xFFFFD36B))
                        Text(mode.blurb, color = InkMuted, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.weight(1f))
                        if (mode == ClaudeRowedMode.ESCAPE) {
                            Text(
                                "Escape fund: %,d of %,d tokens".format(escapeFund.roundToInt(), BobTools.ESCAPE_GOAL),
                                color = Ink, style = numbers(20),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkoutCard(workout: Workout, profile: Profile, onClick: () -> Unit) {
    val total = workout.pieces.sumOf { it.estimatedSeconds(profile) + it.rest.estimatedSeconds() }
    Card(
        Modifier.height(170.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = PanelColor),
    ) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(workout.name, style = MaterialTheme.typography.headlineMedium, color = Ink)
                Spacer(Modifier.weight(1f))
                Text("~${formatClock(total)}", style = numbers(18, color = InkMuted))
            }
            Text(workout.notation, color = InkMuted, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            BandStrip(workout, profile)
        }
    }
}

/** The workout's shape: each piece as a bar in its band colour, sized by estimated time. */
@Composable
private fun BandStrip(workout: Workout, profile: Profile) {
    Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp))) {
        workout.pieces.forEach { piece ->
            val work = piece.estimatedSeconds(profile).toFloat()
            Box(Modifier.weight(work).height(14.dp).background(piece.band?.color() ?: InkMuted))
            val rest = piece.rest.estimatedSeconds().toFloat()
            if (rest > 0) Box(Modifier.weight(rest).height(14.dp).background(Color(0xFF26324A)))
        }
    }
}
