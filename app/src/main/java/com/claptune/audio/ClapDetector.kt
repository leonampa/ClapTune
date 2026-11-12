package com.claptune.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Tunable detector parameters. [sensitivityK] is how many times louder than the noise floor a clap must be. */
data class ClapConfig(
    val sensitivityK: Float = 8f,
    val minAbsRms: Float = 1200f,
    val minGapMs: Int = 150,
    val maxGapMs: Int = 800,
    val cooldownMs: Int = 10_000,
    val clapsRequired: Int = 2,
)

data class ClapResult(
    /** RMS level of the processed frame (0..32767). */
    val level: Float,
    /** Current adaptive noise floor. */
    val noiseFloor: Float,
    /** Trigger threshold currently in effect. */
    val threshold: Float,
    /** A single clap was confirmed in this call. */
    val clap: Boolean,
    /** The full clap pattern (double clap by default) was completed in this call. */
    val triggered: Boolean,
)

/**
 * Pure-Kotlin impulse detector. Feed it consecutive PCM frames (~20 ms each); time is derived from
 * the number of samples seen, so it behaves identically in tests and on a device.
 *
 * A clap is a frame that is far above the noise floor and is followed by a quick drop in energy.
 * Sustained loud sounds (speech, music, tones) keep the energy up and are rejected.
 */
class ClapDetector(
    private val sampleRate: Int = 16_000,
    config: ClapConfig = ClapConfig(),
) {
    @Volatile
    var config: ClapConfig = config

    private var nowMs = 0.0
    private var noiseFloor = 100f

    // Candidate clap awaiting confirmation.
    private var candidateActive = false
    private var candidateOnsetMs = 0.0
    private var candidatePeak = 0f
    private var framesSincePeak = 0
    private var restarts = 0

    // After a rejected (sustained) sound, ignore everything until energy falls back.
    private var blockedUntilQuiet = false
    private var refractoryUntilMs = 0.0
    private var cooldownUntilMs = 0.0
    private val clapTimes = ArrayList<Double>()

    fun reset() {
        nowMs = 0.0
        candidateActive = false
        blockedUntilQuiet = false
        refractoryUntilMs = 0.0
        cooldownUntilMs = 0.0
        clapTimes.clear()
    }

    fun process(samples: ShortArray, length: Int = samples.size): ClapResult {
        val cfg = config
        if (length <= 0) return ClapResult(0f, noiseFloor, threshold(cfg), clap = false, triggered = false)

        var sumSq = 0.0
        for (i in 0 until length) {
            val s = samples[i].toDouble()
            sumSq += s * s
        }
        val rms = sqrt(sumSq / length).toFloat()
        val frameMs = length * 1000.0 / sampleRate
        val frameStart = nowMs
        nowMs += frameMs

        val threshold = threshold(cfg)
        var clap = false
        var triggered = false

        if (blockedUntilQuiet) {
            if (rms < threshold * 0.5f) blockedUntilQuiet = false
        } else if (candidateActive) {
            when {
                rms > candidatePeak && restarts < MAX_RESTARTS -> {
                    // Still rising: this frame is the real peak.
                    candidatePeak = rms
                    framesSincePeak = 0
                    restarts++
                }
                rms >= candidatePeak * DECAY_RATIO -> {
                    // Energy is not dropping quickly: sustained sound, not a clap.
                    candidateActive = false
                    blockedUntilQuiet = true
                }
                else -> {
                    framesSincePeak++
                    if (framesSincePeak >= DECAY_FRAMES) {
                        candidateActive = false
                        clap = true
                        refractoryUntilMs = candidateOnsetMs + REFRACTORY_MS
                        triggered = registerClap(candidateOnsetMs, cfg)
                    }
                }
            }
        } else if (rms > threshold && frameStart >= refractoryUntilMs && frameStart >= cooldownUntilMs) {
            candidateActive = true
            candidateOnsetMs = frameStart
            candidatePeak = rms
            framesSincePeak = 0
            restarts = 0
        }

        // Learn the background level only from frames that are not part of a loud event.
        if (!candidateActive && !blockedUntilQuiet && rms < threshold) {
            val alpha = if (rms > noiseFloor) 0.01f else 0.05f
            noiseFloor = max(MIN_FLOOR, noiseFloor + (rms - noiseFloor) * alpha)
        }

        return ClapResult(rms, noiseFloor, threshold, clap, triggered)
    }

    private fun threshold(cfg: ClapConfig) = max(cfg.minAbsRms, noiseFloor * cfg.sensitivityK)

    private fun registerClap(onsetMs: Double, cfg: ClapConfig): Boolean {
        if (onsetMs < cooldownUntilMs) return false
        if (clapTimes.isNotEmpty()) {
            val gap = onsetMs - clapTimes.last()
            when {
                gap < cfg.minGapMs -> return false // echo of the previous clap
                gap > cfg.maxGapMs -> clapTimes.clear()
            }
        }
        clapTimes.add(onsetMs)
        if (clapTimes.size >= cfg.clapsRequired) {
            clapTimes.clear()
            cooldownUntilMs = nowMs + cfg.cooldownMs
            return true
        }
        return false
    }

    companion object {
        private const val MIN_FLOOR = 50f
        private const val DECAY_RATIO = 0.5f
        private const val DECAY_FRAMES = 3
        private const val MAX_RESTARTS = 3
        private const val REFRACTORY_MS = 100.0

        /** Peak amplitude helper used by the UI meter. */
        fun peak(samples: ShortArray, length: Int): Int {
            var p = 0
            for (i in 0 until length) p = max(p, abs(samples[i].toInt()))
            return p
        }
    }
}
