package com.claptune.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class ClapDetectorTest {
    private val rate = 16_000
    private val frame = 320 // 20 ms

    private fun noiseFrame(amp: Int, rnd: Random) =
        ShortArray(frame) { ((rnd.nextGaussian() * amp).toInt()).coerceIn(-32000, 32000).toShort() }

    /** A clap: loud frame, then a quick exponential decay. */
    private fun clapFrames(rnd: Random) = listOf(14000, 5000, 1200, 400).map { noiseFrame(it, rnd) }

    private fun silence(frames: Int, rnd: Random) = List(frames) { noiseFrame(60, rnd) }

    /** Feeds the signal and returns how many times the pattern triggered. */
    private fun run(frames: List<ShortArray>, config: ClapConfig = ClapConfig()): Int {
        val d = ClapDetector(rate, config)
        return frames.count { d.process(it).triggered }
    }

    private fun gapFrames(gapMs: Int) = gapMs / 20

    private fun doubleClap(gapMs: Int, rnd: Random): List<ShortArray> {
        val out = ArrayList<ShortArray>()
        out += silence(100, rnd) // let the noise floor settle
        out += clapFrames(rnd)
        out += silence(gapFrames(gapMs) - 4, rnd)
        out += clapFrames(rnd)
        out += silence(50, rnd)
        return out
    }

    @Test fun doubleClapIsDetected() {
        for (gap in listOf(200, 300, 500, 700)) {
            assertEquals("gap $gap", 1, run(doubleClap(gap, Random(1))))
        }
    }

    @Test fun singleClapIsIgnored() {
        val rnd = Random(2)
        val s = silence(100, rnd) + clapFrames(rnd) + silence(100, rnd)
        assertEquals(0, run(s))
    }

    @Test fun gapTooLongIsIgnored() {
        assertEquals(0, run(doubleClap(1200, Random(3))))
    }

    @Test fun gapTooShortIsIgnored() {
        assertEquals(0, run(doubleClap(100, Random(4))))
    }

    @Test fun sustainedToneIsIgnored() {
        val rnd = Random(5)
        val tone = ArrayList<ShortArray>()
        var n = 0
        repeat(100) {
            tone += ShortArray(frame) { (12000 * sin(2 * PI * 440 * (n++) / rate)).toInt().toShort() }
        }
        assertEquals(0, run(silence(100, rnd) + tone + silence(50, rnd)))
    }

    @Test fun speechLikeBurstsAreIgnored() {
        val rnd = Random(6)
        val s = ArrayList<ShortArray>()
        s += silence(100, rnd)
        // Syllables: 200 ms of loud noise, a pause, again.
        repeat(3) {
            repeat(10) { s += noiseFrame(6000, rnd) }
            s += silence(8, rnd)
        }
        s += silence(50, rnd)
        assertEquals(0, run(s))
    }

    @Test fun cooldownSuppressesImmediateRetrigger() {
        val rnd = Random(7)
        val s = doubleClap(300, rnd) + doubleClap(300, rnd).drop(100)
        assertEquals(1, run(s))
    }

    @Test fun retriggersAfterCooldown() {
        val rnd = Random(8)
        val cfg = ClapConfig(cooldownMs = 1000)
        val s = doubleClap(300, rnd) + silence(100, rnd) + doubleClap(300, rnd).drop(100)
        assertEquals(2, run(s, cfg))
    }

    @Test fun tripleClapModeNeedsThree() {
        val rnd = Random(9)
        val cfg = ClapConfig(clapsRequired = 3)
        assertEquals(0, run(doubleClap(300, rnd), cfg))
        val triple = silence(100, rnd) + clapFrames(rnd) + silence(11, rnd) + clapFrames(rnd) +
            silence(11, rnd) + clapFrames(rnd) + silence(50, rnd)
        assertEquals(1, run(triple, cfg))
    }

    @Test fun backgroundNoiseDoesNotTrigger() {
        val rnd = Random(10)
        assertEquals(0, run(List(500) { noiseFrame(500, rnd) }))
    }

    @Test fun clapsAreReportedIndividually() {
        val d = ClapDetector(rate)
        val rnd = Random(11)
        val claps = (silence(100, rnd) + clapFrames(rnd) + silence(20, rnd)).count { d.process(it).clap }
        assertTrue(claps == 1)
        assertFalse(d.process(silence(1, rnd)[0]).triggered)
    }
}
