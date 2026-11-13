package name.onetrue.supertuxrower.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import name.onetrue.supertuxrower.model.describe
import name.onetrue.supertuxrower.model.formatClock
import name.onetrue.supertuxrower.model.formatSplit
import name.onetrue.supertuxrower.model.penguinName
import name.onetrue.supertuxrower.workout.WorkoutRunner
import kotlin.math.roundToInt

@Composable
fun SummaryScreen(runner: WorkoutRunner, onDone: () -> Unit) {
    val rowed = runner.results.filter { it.seconds > 0 }
    val workSeconds = rowed.sumOf { it.seconds }
    val workDistance = rowed.sumOf { it.distance }
    val banded = rowed.filter { it.piece.band != null }
    val inBand = if (banded.isEmpty()) null else banded.sumOf { it.inBand } / banded.sumOf { it.seconds }

    Column(Modifier.fillMaxSize().background(Deep).padding(48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${runner.workout.name} done", style = MaterialTheme.typography.displaySmall, color = Ink)
            Spacer(Modifier.weight(1f))
            Button(onDone) { Text("Done") }
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(56.dp)) {
            Total("Time", formatClock(runner.elapsed))
            Total("Distance", "${runner.distance.roundToInt()} m")
            Total("Avg split", if (workSeconds > 0) formatSplit(workDistance / workSeconds) else "–")
            Total("Calories", "${runner.calories.roundToInt()}")
            Total("In band", inBand?.let { "${(it * 100).roundToInt()}%" } ?: "–")
        }
        Spacer(Modifier.height(32.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rowed) { r ->
                Row(
                    Modifier.fillMaxWidth().background(PanelColor, RoundedCornerShape(8.dp)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(20.dp).background(r.piece.band?.color() ?: InkMuted, RoundedCornerShape(4.dp)))
                    Spacer(Modifier.width(16.dp))
                    Text(r.piece.tag.label, Modifier.width(140.dp), color = InkMuted)
                    Text("${r.piece.goal.describe()} · ${r.piece.band?.penguinName() ?: "Free"}", Modifier.weight(1f), style = numbers(22))
                    Text(formatClock(r.seconds), Modifier.width(110.dp), style = numbers(22))
                    Text("${r.distance.roundToInt()} m", Modifier.width(120.dp), style = numbers(22))
                    Text(formatSplit(if (r.seconds > 0) r.distance / r.seconds else 0.0), Modifier.width(130.dp), style = numbers(22))
                    Text(r.inBandShare?.let { "${(it * 100).roundToInt()}% in band" } ?: "", Modifier.width(180.dp), style = numbers(22, color = InkMuted))
                }
            }
        }
    }
}

@Composable
private fun Total(label: String, value: String) {
    Column {
        Text(label.uppercase(), color = InkMuted, fontSize = 16.sp, letterSpacing = 2.sp)
        Text(value, style = numbers(40, FontWeight.Bold))
    }
}
