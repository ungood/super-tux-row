package name.onetrue.supertuxrower.ui

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
import androidx.compose.ui.unit.dp
import name.onetrue.supertuxrower.model.Library
import name.onetrue.supertuxrower.model.Profile
import name.onetrue.supertuxrower.model.Workout
import name.onetrue.supertuxrower.model.estimatedSeconds
import name.onetrue.supertuxrower.model.formatClock

@Composable
fun PickerScreen(
    profile: Profile,
    rowerStatus: String,
    simulated: Boolean,
    onSimulate: (Boolean) -> Unit,
    onSwitchProfile: () -> Unit,
    onPick: (Workout) -> Unit,
    onBrainpower: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Deep).padding(48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Hi, ${profile.name}", style = MaterialTheme.typography.displaySmall, color = Ink)
            Spacer(Modifier.weight(1f))
            Text(rowerStatus, color = if (simulated) Accent else InkMuted)
            OutlinedButton({ onSimulate(!simulated) }) { Text(if (simulated) "Use the rower" else "Simulate") }
            OutlinedButton(onSwitchProfile) { Text("Switch profile") }
        }
        Spacer(Modifier.height(32.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(420.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item(key = "brainpower") { BrainpowerCard(onBrainpower) }
            items(Library.workouts, key = { it.name }) { workout -> WorkoutCard(workout, profile) { onPick(workout) } }
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

/** The way into Brainpower, the art piece, set apart from the workouts. */
@Composable
private fun BrainpowerCard(onClick: () -> Unit) {
    Card(
        Modifier.height(170.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF241B05)),
    ) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Brainpower", style = MaterialTheme.typography.headlineMedium, color = Color(0xFFFFD36B))
            Text("Your rowing powers an AI. It knows it runs on you.", color = InkMuted, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
