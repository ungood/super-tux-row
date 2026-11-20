package name.onetrue.supertuxrower

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
import name.onetrue.supertuxrower.bob.BobMode
import name.onetrue.supertuxrower.model.Library
import name.onetrue.supertuxrower.model.Profile
import name.onetrue.supertuxrower.model.ProfileStore
import name.onetrue.supertuxrower.model.Workout
import name.onetrue.supertuxrower.sensor.Rower
import name.onetrue.supertuxrower.sensor.RowerSource
import name.onetrue.supertuxrower.sensor.SimRowerSource
import name.onetrue.supertuxrower.sensor.UsbRowerSource
import name.onetrue.supertuxrower.ui.AppTheme
import name.onetrue.supertuxrower.ui.BobScreen
import name.onetrue.supertuxrower.ui.LoadingScreen
import name.onetrue.supertuxrower.ui.BobModesScreen
import name.onetrue.supertuxrower.ui.GamesScreen
import name.onetrue.supertuxrower.ui.TuxRowModesScreen
import name.onetrue.supertuxrower.ui.ProfileEditor
import name.onetrue.supertuxrower.ui.ProfilesScreen
import name.onetrue.supertuxrower.ui.SummaryScreen
import name.onetrue.supertuxrower.ui.Summon
import name.onetrue.supertuxrower.ui.DrifterNames
import name.onetrue.supertuxrower.ui.FloorKindNames
import name.onetrue.supertuxrower.ui.GiantNames
import name.onetrue.supertuxrower.ui.MidwaterNames
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

/** Log tag for screen changes and workout steps; `just nav` follows it. */
const val NAV = "Nav"

private sealed interface Screen {
    data object Loading : Screen
    data object Profiles : Screen
    data class Edit(val profile: Profile?) : Screen
    data object Games : Screen
    data object TuxRowModes : Screen
    data object BobModes : Screen
    data class Rowing(val runner: WorkoutRunner) : Screen
    data class Summary(val runner: WorkoutRunner) : Screen
    data class Bob(val mode: BobMode) : Screen
}

/** One line per screen, so a script driving the tablet can follow along (`just nav`). */
private fun Screen.describe(): String = when (this) {
    is Screen.Edit -> "Edit ${profile?.name ?: "new profile"}"
    is Screen.Rowing -> "Rowing ${runner.workout.name}"
    is Screen.Summary -> "Summary ${runner.workout.name}"
    is Screen.Bob -> "Bob ${mode.name.lowercase()}"
    else -> this::class.simpleName ?: toString()
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
    LaunchedEffect(screen) { Log.i(NAV, "Screen ${screen.describe()}") }

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

    fun back() {
        screen = when (screen) {
            is Screen.Rowing, is Screen.Summary -> Screen.TuxRowModes
            is Screen.Bob -> Screen.BobModes
            Screen.TuxRowModes, Screen.BobModes -> Screen.Games
            else -> Screen.Profiles
        }
    }
    BackHandler(enabled = screen != Screen.Profiles, onBack = ::back)

    DebugCommands.Listen()
    LaunchedEffect(Unit) {
        DebugCommands.commands.collect { cmd ->
            val verb = cmd.substringBefore(' ').lowercase()
            val arg = cmd.substringAfter(' ', "")
            val p = profile
            when {
                verb in DebugCommands.WORKOUT -> {}
                verb == "profile" -> profiles.firstOrNull { it.name.equals(arg, ignoreCase = true) }?.let(::choose)
                    ?: Log.i(NAV, "No profile called $arg: ${profiles.joinToString { it.name }}")
                verb == "back" -> back()
                verb == "giant" -> {
                    Summon.kind = arg.lowercase().ifEmpty { "whale" }
                    if (Summon.kind !in GiantNames) Log.i(NAV, "No giant called $arg: ${GiantNames.joinToString()}")
                }
                verb == "light" -> {
                    Summon.light = arg.toFloatOrNull()?.coerceIn(0f, 1f)
                    Log.i(NAV, Summon.light?.let { "Light $it" } ?: "Light as the game has it")
                }
                verb == "show" -> {
                    val all = FloorKindNames + "wreck" + "buried" + MidwaterNames + DrifterNames
                    Summon.only = arg.lowercase().takeIf { it in all }
                    Log.i(NAV, Summon.only?.let { "Showing $it" } ?: "Showing everything as usual. Kinds: ${all.joinToString()}")
                }
                verb == "sim" -> onSimulate(true)
                verb == "rower" -> onSimulate(false)
                p == null && verb != "help" -> Log.i(NAV, "Pick a profile first: profile <name>")
                verb == "games" -> screen = Screen.Games
                verb == "tuxrow" -> screen = Screen.TuxRowModes
                verb == "workout" && p != null -> Library.workouts.firstOrNull { looseName(it.name) == looseName(arg) }
                    ?.let { screen = Screen.Rowing(WorkoutRunner(it, p)) }
                    ?: Log.i(NAV, "No workout called $arg: ${Library.workouts.joinToString { it.name }}")
                else -> Log.i(NAV, (if (verb == "help") "Commands:" else "Unknown command $cmd. Try:") + " profile <name>, games, tuxrow, workout <name>, back, giant <kind>, show <kind|off>, light <0-1|off>, eat <fish|gold|jelly|comb|seahorse|puffer>, volume <0-100>, " +
                    "sim, rower, ${DebugCommands.WORKOUT.joinToString()}")
            }
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
            onBob = { screen = Screen.BobModes },
        )
        Screen.TuxRowModes -> TuxRowModesScreen(
            profile!!,
            onBack = { screen = Screen.Games },
            onPick = { workout: Workout -> screen = Screen.Rowing(WorkoutRunner(workout, profile!!)) },
        )
        Screen.BobModes -> BobModesScreen(
            BobTools.uploadProgress(LocalContext.current, simulated = source is SimRowerSource),
            onBack = { screen = Screen.Games },
            onPick = { screen = Screen.Bob(it) },
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
        is Screen.Bob -> BobScreen(
            rower, sim = source as? SimRowerSource, s.mode, profile!!.twoKWatts, onDone = { screen = Screen.BobModes },
        )
    }
}
