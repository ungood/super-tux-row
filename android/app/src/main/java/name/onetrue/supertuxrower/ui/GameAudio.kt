package name.onetrue.supertuxrower.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import name.onetrue.supertuxrower.game.Cue
import name.onetrue.supertuxrower.game.Synth
import java.io.File
import kotlin.math.abs

/**
 * Plays the game's sounds, which [Synth] makes when this is created. It uses a SoundPool rather
 * than an AudioTrack per sound: there are over twenty sounds, and Android 9 runs out of tracks
 * around 32 for the whole device; the pool shares a few voices and lets quick repeats overlap.
 * The sounds are made and loaded on a background thread; until they're ready, [play] does nothing.
 * If audio can't start, it logs and stays silent rather than crashing the game.
 */
class GameAudio(context: Context) {
    private val dir = File(context.cacheDir, "sounds")
    @Volatile private var pool: SoundPool? = null
    @Volatile private var ready = false
    private val ids = HashMap<String, Int>()
    private var ambienceId = 0
    private var ambienceStream = 0
    private var ambienceVolume = -1f
    @Volatile private var released = false

    /** The game's own volume, 0 to 1, under the tablet's media volume; it scales everything. */
    var volume = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            // The ambience picks it up on its next call.
            ambienceVolume = -1f
        }

    init {
        Thread({ load() }, "GameAudio").start()
    }

    private fun load() {
        try {
            val pool = SoundPool.Builder()
                .setMaxStreams(MAX_STREAMS)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()
            dir.mkdirs()
            var pending = 0
            val lock = Object()
            pool.setOnLoadCompleteListener { _, _, status ->
                if (status != 0) Log.w(TAG, "A sound failed to load: $status")
                synchronized(lock) {
                    pending--
                    lock.notifyAll()
                }
            }
            fun add(key: String, samples: ShortArray): Int {
                val file = File(dir, "$key.wav")
                file.writeBytes(Synth.wav(samples))
                synchronized(lock) { pending++ }
                return pool.load(file.path, 1)
            }
            for (cue in Cue.entries) {
                val variants = if (cue == Cue.CATCH) Synth.CATCH_STEPS else 1
                for (v in 0 until variants) ids[key(cue, v)] = add(key(cue, v), Synth.render(cue, v))
            }
            ambienceId = add("ambience", Synth.ambience())
            // Load callbacks come on the main thread (this one has no looper), so waiting here is safe.
            val deadline = System.currentTimeMillis() + 10_000
            synchronized(lock) {
                while (pending > 0 && !released && System.currentTimeMillis() < deadline) lock.wait(500)
            }
            if (pending > 0) Log.w(TAG, "$pending sounds still loading; carrying on without them")
            if (released) {
                pool.release()
                return
            }
            this.pool = pool
            ready = true
        } catch (e: Exception) {
            Log.e(TAG, "Sound is off: couldn't set up audio", e)
        }
    }

    /** Plays [cue]; [variant] picks the pitch of a [Cue.CATCH]. */
    fun play(cue: Cue, variant: Int = 0, volume: Float = 1f) {
        if (!ready || this.volume <= 0f) return
        val id = ids[key(cue, if (cue == Cue.CATCH) variant.coerceIn(0, Synth.CATCH_STEPS - 1) else 0)] ?: return
        val v = volume.coerceIn(0f, 1f) * this.volume
        // Cues outrank the ambience, so it's what drops out if the voices run short.
        pool?.play(id, v, v, 2, 0, 1f)
    }

    /**
     * Turns the underwater bed on or off at [volume]. Cheap to call every frame: the pool is only
     * told when the volume really changes.
     */
    fun ambience(on: Boolean, volume: Float) {
        val pool = pool ?: return
        if (!ready) return
        if (on && ambienceStream == 0) {
            ambienceStream = pool.play(ambienceId, 0f, 0f, 1, -1, 1f)
            ambienceVolume = -1f
        } else if (!on && ambienceStream != 0) {
            pool.stop(ambienceStream)
            ambienceStream = 0
            return
        }
        if (!on) return
        val v = volume.coerceIn(0f, 1f) * this.volume
        if (abs(v - ambienceVolume) > 0.01f) applyAmbience(v)
    }

    private fun applyAmbience(v: Float) {
        if (ambienceStream == 0) return
        pool?.setVolume(ambienceStream, v, v)
        ambienceVolume = v
    }

    fun release() {
        released = true
        ready = false
        pool?.release()
        pool = null
        ambienceStream = 0
    }

    private fun key(cue: Cue, variant: Int) = if (cue == Cue.CATCH) "catch$variant" else cue.name.lowercase()

    private companion object {
        const val TAG = "GameAudio"
        const val MAX_STREAMS = 8
    }
}
