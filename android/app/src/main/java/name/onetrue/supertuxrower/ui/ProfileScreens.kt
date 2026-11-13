package name.onetrue.supertuxrower.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import name.onetrue.supertuxrower.model.Band
import name.onetrue.supertuxrower.model.Profile
import name.onetrue.supertuxrower.model.StartingLevel
import name.onetrue.supertuxrower.model.formatClock
import name.onetrue.supertuxrower.model.formatSplit
import name.onetrue.supertuxrower.model.penguinName

@Composable
fun ProfilesScreen(
    profiles: List<Profile>,
    lastUsedId: String?,
    onPick: (Profile) -> Unit,
    onEdit: (Profile) -> Unit,
    onNew: () -> Unit,
    onOpenAndroid: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Deep).padding(48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Who's rowing?", style = MaterialTheme.typography.displayMedium, color = Ink)
            Spacer(Modifier.weight(1f))
            // The way out to the rest of the tablet when this app is the home app.
            TextButton(onOpenAndroid) { Text("Android") }
        }
        Spacer(Modifier.height(32.dp))
        // The last-used profile comes first and is highlighted, so it's one tap away.
        val ordered = profiles.sortedByDescending { it.id == lastUsedId }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(340.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(ordered, key = { it.id }) { p ->
                ProfileCard(p, p.id == lastUsedId, onClick = { onPick(p) }, onEdit = { onEdit(p) })
            }
            item {
                Card(
                    Modifier.height(180.dp).clickable(onClick = onNew),
                    colors = CardDefaults.cardColors(containerColor = PanelColor),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("+  New profile", style = MaterialTheme.typography.headlineSmall, color = InkMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: Profile, lastUsed: Boolean, onClick: () -> Unit, onEdit: () -> Unit) {
    Card(
        Modifier.height(180.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = PanelColor),
        border = if (lastUsed) BorderStroke(3.dp, Accent) else null,
    ) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text(profile.name, style = MaterialTheme.typography.headlineLarge, color = Ink)
            Text("2k ${formatClock(profile.twoKSeconds.toDouble())}", style = MaterialTheme.typography.titleMedium, color = InkMuted)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (lastUsed) Text("Last rowed", color = Accent, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onEdit) { Text("Edit") }
            }
        }
    }
}

@Composable
fun ProfileEditor(initial: Profile?, onSave: (Profile) -> Unit, onDelete: (() -> Unit)?, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var twoK by remember { mutableIntStateOf(initial?.twoKSeconds ?: StartingLevel.FIT.twoKSeconds) }
    val preview = (initial ?: Profile(name = name, twoKSeconds = twoK)).copy(name = name, twoKSeconds = twoK)

    Row(Modifier.fillMaxSize().background(Deep).padding(48.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(if (initial == null) "New profile" else "Edit profile", style = MaterialTheme.typography.displaySmall, color = Ink)
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("Starting level", style = MaterialTheme.typography.titleMedium, color = InkMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StartingLevel.entries.forEach { level ->
                    val label = "${level.label} · ${formatClock(level.twoKSeconds.toDouble())}"
                    if (twoK == level.twoKSeconds) Button({ twoK = level.twoKSeconds }) { Text(label) }
                    else OutlinedButton({ twoK = level.twoKSeconds }) { Text(label) }
                }
            }

            Text("Reference 2k time", style = MaterialTheme.typography.titleMedium, color = InkMuted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton({ twoK = (twoK - 5).coerceAtLeast(300) }) { Text("−5 s") }
                Text(formatClock(twoK.toDouble()), style = numbers(40))
                OutlinedButton({ twoK = (twoK + 5).coerceAtMost(900) }) { Text("+5 s") }
            }
            Text(
                "Your bands are fractions of the power you hold over a 2k. Faster 2k, faster bands.",
                color = InkMuted, style = MaterialTheme.typography.bodyMedium,
            )

            // Kept high on the screen so the on-screen keyboard doesn't cover it.
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button({ onSave(preview) }, enabled = name.isNotBlank()) { Text("Save") }
                OutlinedButton(onCancel) { Text("Cancel") }
                if (onDelete != null) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onDelete) { Text("Delete profile") }
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text("Your bands", style = MaterialTheme.typography.titleLarge, color = Ink)
            Spacer(Modifier.height(16.dp))
            BandTable(preview)
        }
    }
}

@Composable
fun BandTable(profile: Profile) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Band.entries.reversed().forEach { band ->
            val slow = formatSplit(profile.speedAt(band.floor))
            val fast = formatSplit(profile.speedAt(band.ceiling))
            val range = when (band) {
                Band.REST -> "slower than $fast"
                Band.AN -> "faster than $slow"
                else -> "$slow – $fast"
            }
            val watts = when (band) {
                Band.REST -> "< ${(profile.twoKWatts * band.ceiling).toInt()} W"
                Band.AN -> "> ${(profile.twoKWatts * band.floor).toInt()} W"
                else -> "${(profile.twoKWatts * band.floor).toInt()}–${(profile.twoKWatts * band.ceiling).toInt()} W"
            }
            Row(
                Modifier.fillMaxWidth().background(PanelColor, RoundedCornerShape(8.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(20.dp).background(band.color(), RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(16.dp))
                Text(band.penguinName(), Modifier.width(120.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Ink)
                Text(band.rowingName, Modifier.width(60.dp), color = InkMuted)
                Text(range, Modifier.weight(1f), style = numbers(18, FontWeight.Normal))
                Text(watts, style = numbers(18, FontWeight.Normal, InkMuted))
            }
        }
    }
}
