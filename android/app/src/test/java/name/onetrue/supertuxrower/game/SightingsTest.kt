package name.onetrue.supertuxrower.game

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SightingsTest {
    private fun watch(light: Double, openness: Double, seconds: Double = 3000.0): Set<String> {
        val sightings = Sightings(Random(4))
        val seen = mutableSetOf<String>()
        var t = 0.0
        while (t < seconds) {
            sightings.update(0.1, light, openness)
            sightings.current?.let { seen += it.kind.name }
            t += 0.1
        }
        return seen
    }

    @Test
    fun `nothing big in a plain cave, and no whales or hunters where it's only half open`() {
        assertTrue(watch(light = 0.8, openness = 0.42).isEmpty())
        val seen = watch(light = 0.8, openness = 0.55)
        assertTrue("seen $seen", seen.isNotEmpty())
        assertTrue("seen $seen", seen.none { it in setOf("whale", "orca", "whaleshark", "belugas") })
    }

    @Test
    fun `in open water near the surface, whales but no anglerfish`() {
        val seen = watch(light = 0.9, openness = 1.0)
        assertTrue("seen $seen", "whale" in seen)
        assertTrue("seen $seen", "anglerfish" !in seen)
    }

    @Test
    fun `the anglerfish lives in the dark`() {
        assertTrue("anglerfish" in watch(light = 0.2, openness = 0.6))
    }

    @Test
    fun `one at a time, with a gap between`() {
        val sightings = Sightings(Random(1))
        var t = 0.0
        while (t < Sightings.FIRST - 1) {
            sightings.update(0.1, 0.9, 1.0)
            t += 0.1
        }
        assertNull(sightings.current)
    }
}
