package name.onetrue.supertuxrower

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import name.onetrue.supertuxrower.model.Profile
import name.onetrue.supertuxrower.model.ProfileStore
import name.onetrue.supertuxrower.model.Workout
import name.onetrue.supertuxrower.sensor.Rower
import name.onetrue.supertuxrower.sensor.RowerSource
import name.onetrue.supertuxrower.sensor.SimRowerSource
import name.onetrue.supertuxrower.sensor.UsbRowerSource
import name.onetrue.supertuxrower.ui.AppTheme
import name.onetrue.supertuxrower.ui.PickerScreen
import name.onetrue.supertuxrower.ui.ProfileEditor
import name.onetrue.supertuxrower.ui.ProfilesScreen
import name.onetrue.supertuxrower.ui.SummaryScreen
import name.onetrue.supertuxrower.ui.WorkoutScreen
import name.onetrue.supertuxrower.workout.WorkoutRunner

class MainActivity : ComponentActivity() {
    private val rower = Rower()
    private var source by mutableStateOf<RowerSource?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val store = ProfileStore(this)
        // Launch with `--ez simulate true` (see the justfile's `sim`) to start on the simulated rower.
        useSimulator(intent.getBooleanExtra(EXTRA_SIMULATE, false) || !UsbRowerSource.sensorPresent(this))
        setContent {
            AppTheme { App(store, rower, source, ::useSimulator) }
        }
    }

    override fun onDestroy() {
        source?.stop()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SIMULATE = "simulate"
    }

    private fun useSimulator(simulate: Boolean) {
        source?.stop()
        source = (if (simulate) SimRowerSource(rower) else UsbRowerSource(this, rower)).also { it.start() }
    }
}

private sealed interface Screen {
    data object Profiles : Screen
    data class Edit(val profile: Profile?) : Screen
    data object Picker : Screen
    data class Rowing(val runner: WorkoutRunner) : Screen
    data class Summary(val runner: WorkoutRunner) : Screen
}

@Composable
private fun App(store: ProfileStore, rower: Rower, source: RowerSource?, onSimulate: (Boolean) -> Unit) {
    var profiles by remember { mutableStateOf(store.load()) }
    var lastUsed by remember { mutableStateOf(store.lastUsedId) }
    var profile by remember { mutableStateOf<Profile?>(null) }
    var screen by remember { mutableStateOf<Screen>(if (profiles.isEmpty()) Screen.Edit(null) else Screen.Profiles) }

    fun choose(p: Profile) {
        profile = p
        store.lastUsedId = p.id
        lastUsed = p.id
        screen = Screen.Picker
    }

    BackHandler(enabled = screen != Screen.Profiles) {
        screen = when (screen) {
            is Screen.Rowing, is Screen.Summary -> Screen.Picker
            else -> Screen.Profiles
        }
    }

    when (val s = screen) {
        Screen.Profiles -> ProfilesScreen(
            profiles, lastUsed,
            onPick = ::choose,
            onEdit = { screen = Screen.Edit(it) },
            onNew = { screen = Screen.Edit(null) },
        )
        is Screen.Edit -> ProfileEditor(
            s.profile,
            onSave = {
                profiles = store.upsert(it)
                choose(it)
            },
            onDelete = s.profile?.let { p ->
                {
                    profiles = store.delete(p.id)
                    lastUsed = store.lastUsedId
                    screen = Screen.Profiles
                }
            },
            onCancel = { screen = if (profiles.isEmpty()) Screen.Edit(null) else Screen.Profiles },
        )
        Screen.Picker -> PickerScreen(
            profile!!,
            rowerStatus = rower.status,
            simulated = source?.simulated == true,
            onSimulate = onSimulate,
            onSwitchProfile = { screen = Screen.Profiles },
            onPick = { workout: Workout -> screen = Screen.Rowing(WorkoutRunner(workout, profile!!)) },
        )
        is Screen.Rowing -> WorkoutScreen(
            s.runner, rower,
            sim = source as? SimRowerSource,
            compact = profile!!.compactOverlay,
            onCompactChange = { compact ->
                val updated = profile!!.copy(compactOverlay = compact)
                profiles = store.upsert(updated)
                profile = updated
            },
            onDone = { screen = Screen.Summary(s.runner) },
        )
        is Screen.Summary -> SummaryScreen(s.runner, onDone = { screen = Screen.Picker })
    }
}
