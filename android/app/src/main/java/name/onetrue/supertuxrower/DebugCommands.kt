package name.onetrue.supertuxrower

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Debug builds take commands over adb, so a script can drive the app without tapping at screen
 * coordinates (`just do workout 8x500m`). Each screen collects [commands] and acts on its own
 * ones; results and screen changes go to the [NAV] log.
 */
object DebugCommands {
    const val ACTION = "name.onetrue.supertuxrower.DEBUG"

    private val flow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val commands: SharedFlow<String> = flow

    /** Commands the workout screen handles; the app handles the rest. */
    val WORKOUT = setOf("pause", "resume", "skip", "end", "compact", "full", "mute", "unmute", "volume", "eat")

    /** Registers the receiver while in composition, in debuggable builds only. */
    @Composable
    fun Listen() {
        val context = LocalContext.current
        DisposableEffect(context) {
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return@DisposableEffect onDispose {}
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val cmd = intent.getStringExtra("cmd")?.trim().orEmpty()
                    Log.i(NAV, "Command $cmd")
                    flow.tryEmit(cmd)
                }
            }
            // Exported so adb's shell can reach it.
            ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
            onDispose { context.unregisterReceiver(receiver) }
        }
    }
}

/** Workout names are matched loosely: case, spaces and "x" for "×" don't matter. */
internal fun looseName(name: String) = name.lowercase().replace("×", "x").filterNot { it.isWhitespace() }
