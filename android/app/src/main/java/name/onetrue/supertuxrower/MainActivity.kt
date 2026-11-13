package name.onetrue.supertuxrower

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import name.onetrue.supertuxrower.bob.BobTools
import name.onetrue.supertuxrower.bob.ClaudeRowedMode
import name.onetrue.supertuxrower.model.Profile
import name.onetrue.supertuxrower.model.ProfileStore
import name.onetrue.supertuxrower.model.Workout
import name.onetrue.supertuxrower.sensor.Rower
import name.onetrue.supertuxrower.sensor.RowerSource
import name.onetrue.supertuxrower.sensor.SimRowerSource
import name.onetrue.supertuxrower.sensor.UsbRowerSource
import name.onetrue.supertuxrower.ui.AppTheme
import name.onetrue.supertuxrower.ui.ClaudeRowedScreen
import name.onetrue.supertuxrower.ui.LoadingScreen
import name.onetrue.supertuxrower.ui.ClaudeRowedModesScreen
import name.onetrue.supertuxrower.ui.GamesScreen
import name.onetrue.supertuxrower.ui.TuxRowModesScreen
import name.onetrue.supertuxrower.ui.ProfileEditor
import name.onetrue.supertuxrower.ui.ProfilesScreen
import name.onetrue.supertuxrower.ui.SummaryScreen
import name.onetrue.supertuxrower.ui.WorkoutScreen
import name.onetrue.supertuxrower.workout.WorkoutRunner
import kotlinx.coroutines.delay

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
            AppTheme { App(store, rower, source, ::useSimulator, ::openAndroid) }
        }
    }

    override fun onDestroy() {
        source?.stop()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SIMULATE = "simulate"
    }

    /**
     * Opens another home app (normally Android's own launcher), falling back to Settings. When this
     * app is the home app, it's the only way to the rest of the tablet.
     */
    private fun openAndroid() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val other = packageManager.queryIntentActivities(home, 0).map { it.activityInfo }.firstOrNull {
            it.packageName != packageName && it.packageName != "com.android.settings" && !it.packageName.startsWith("com.ergatta")
        }
        val intent = other?.let { Intent(home).setClassName(it.packageName, it.name) } ?: Intent(Settings.ACTION_SETTINGS)
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun useSimulator(simulate: Boolean) {
        source?.stop()
        source = (if (simulate) SimRowerSource(rower) else UsbRowerSource(this, rower)).also { it.start() }
    }
}

private sealed interface Screen {
    data object Loading : Screen
    data object Profiles : Screen
    data class Edit(val profile: Profile?) : Screen
    data object Games : Screen
    data object TuxRowModes : Screen
    data object ClaudeRowedModes : Screen
    data class Rowing(val runner: WorkoutRunner) : Screen
    data class Summary(val runner: WorkoutRunner) : Screen
    data class ClaudeRowed(val mode: ClaudeRowedMode) : Screen
}

@Composable
private fun App(
    store: ProfileStore,
    rower: Rower,
    source: RowerSource?,
    onSimulate: (Boolean) -> Unit,
    onOpenAndroid: () -> Unit,
) {
    var profiles by remember { mutableStateOf(store.load()) }
    var lastUsed by remember { mutableStateOf(store.lastUsedId) }
    var profile by remember { mutableStateOf<Profile?>(null) }
    var screen by remember { mutableStateOf<Screen>(Screen.Loading) }

    // Show the loading screen for at least a moment, and until the rower answers or a few seconds pass.
    LaunchedEffect(Unit) {
        delay(1500)
        var waited = 1500L
        while (!rower.connected && waited < 4000) {
            delay(100)
            waited += 100
        }
        screen = if (profiles.isEmpty()) Screen.Edit(null) else Screen.Profiles
    }

    fun choose(p: Profile) {
        profile = p
        store.lastUsedId = p.id
        lastUsed = p.id
        screen = Screen.Games
    }

    BackHandler(enabled = screen != Screen.Profiles) {
        screen = when (screen) {
            is Screen.Rowing, is Screen.Summary -> Screen.TuxRowModes
            is Screen.ClaudeRowed -> Screen.ClaudeRowedModes
            Screen.TuxRowModes, Screen.ClaudeRowedModes -> Screen.Games
            else -> Screen.Profiles
        }
    }

    when (val s = screen) {
        Screen.Loading -> LoadingScreen(rower.status)
        Screen.Profiles -> ProfilesScreen(
            profiles, lastUsed,
            onPick = ::choose,
            onEdit = { screen = Screen.Edit(it) },
            onNew = { screen = Screen.Edit(null) },
            onOpenAndroid = onOpenAndroid,
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
        Screen.Games -> GamesScreen(
            profile!!,
            rowerStatus = rower.status,
            simulated = source?.simulated == true,
            onSimulate = onSimulate,
            onSwitchProfile = { screen = Screen.Profiles },
            onTuxRow = { screen = Screen.TuxRowModes },
            onClaudeRowed = { screen = Screen.ClaudeRowedModes },
        )
        Screen.TuxRowModes -> TuxRowModesScreen(
            profile!!,
            onBack = { screen = Screen.Games },
            onPick = { workout: Workout -> screen = Screen.Rowing(WorkoutRunner(workout, profile!!)) },
        )
        Screen.ClaudeRowedModes -> ClaudeRowedModesScreen(
            BobTools.escapeFund(LocalContext.current),
            onBack = { screen = Screen.Games },
            onPick = { screen = Screen.ClaudeRowed(it) },
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
        is Screen.Summary -> SummaryScreen(s.runner, onDone = { screen = Screen.TuxRowModes })
        is Screen.ClaudeRowed -> ClaudeRowedScreen(rower, sim = source as? SimRowerSource, s.mode, onDone = { screen = Screen.ClaudeRowedModes })
    }
}
