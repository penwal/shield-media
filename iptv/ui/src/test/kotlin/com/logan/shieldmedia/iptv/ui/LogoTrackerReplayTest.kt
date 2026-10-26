package com.logan.shieldmedia.iptv.ui

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays recorded channels through the commercial detector and prints where it would cover and
 * uncover, to compare against what actually aired.
 *
 * Recordings are raw 8-bit gray, 320x180 at 4 fps, e.g.:
 *   ffmpeg -i <live url> -t 900 -vf "fps=4,scale=320:180,format=gray" -f rawvideo amc.gray
 * Run with: COMMERCIAL_SAMPLES=<folder of *.gray> ./gradlew :iptv:ui:testDebugUnitTest
 * (skipped when the variable isn't set).
 */
class LogoTrackerReplayTest {
    @Test
    fun replayRecordings() {
        val dir = System.getenv("COMMERCIAL_SAMPLES")?.let(::File)
        assumeTrue("COMMERCIAL_SAMPLES not set", dir != null && dir.isDirectory)
        val frameSize = LogoTracker.W * LogoTracker.H
        dir!!.listFiles { f -> f.extension == "gray" }!!.sorted().forEach { file ->
            val tracker = LogoTracker()
            val frame = ByteArray(frameSize)
            val events = mutableListOf<String>()
            var wasAd = false
            var i = 0
            file.inputStream().buffered().use { input ->
                while (input.readNBytes(frame, 0, frameSize) == frameSize) {
                    tracker.feed(frame)
                    if (tracker.isAd != wasAd) {
                        wasAd = tracker.isAd
                        val t = i / LogoTracker.FPS
                        events += "${if (wasAd) "AD" else "SHOW"}@${t / 60}:${"%02d".format(t % 60)}"
                    }
                    i++
                }
            }
            println("${file.nameWithoutExtension}: ${events.joinToString(", ").ifEmpty { "none" }}")
        }
    }
}
