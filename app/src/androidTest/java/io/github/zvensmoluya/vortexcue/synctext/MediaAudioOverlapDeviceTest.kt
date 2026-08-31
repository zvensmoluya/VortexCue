package io.github.zvensmoluya.vortexcue.synctext

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaAudioOverlapDeviceTest {
    @Test
    fun productionDecoderKeepsOverlapPcmAligned() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outputDirectory =
            requireNotNull(context.getExternalFilesDir("device-validation/asr-overlap")).apply(File::mkdirs)
        val source = outputDirectory.resolve("source.m4a")
        require(source.isFile) { "Push the validation source to ${source.absolutePath}" }
        val extractor = PlatformAudioWindowExtractor(context)
        val tracks = extractor.inspect(Uri.fromFile(source))
        assertEquals(1, tracks.size)
        val report = mutableListOf<String>()
        report += "device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        report += "sdk=${android.os.Build.VERSION.SDK_INT} release=${android.os.Build.VERSION.RELEASE}"
        report += "source=${source.name} bytes=${source.length()} sha256=${source.sha256()}"
        report +=
            "track=${tracks.single().mimeType} channels=${tracks.single().channelCount} " +
                "sampleRate=${tracks.single().sampleRate} durationMs=${tracks.single().durationMs}"

        listOf(AsrProfile.OpenRouterQwen3Asr17BV1, AsrProfile.OpenRouterWhisperV1).forEach { profile ->
            val plans =
                FixedAsrWindowPlanner(
                    targetUploadMs = profile.windowDurationMs,
                    overlapMs = profile.windowOverlapMs,
                ).plan(MediaDurationMs)
            assertEquals(2, plans.size)
            val extracted =
                plans.map { plan ->
                    val output = outputDirectory.resolve("${profile.name}-${plan.ordinal}.wav")
                    extractor.extract(Uri.fromFile(source), tracks.single().selectionId, plan, output)
                }
            extracted.zip(plans).forEach { (window, plan) ->
                val expectedFrames = plan.uploadDurationMs * OutputSampleRate / 1_000L
                assertEquals(expectedFrames, window.pcmFrames)
                assertEquals(WavHeaderBytes + expectedFrames * BytesPerFrame, window.file.length())
            }

            val overlapFrames = profile.windowOverlapMs * OutputSampleRate / 1_000L
            val left =
                readPcm16(
                    extracted[0].file,
                    firstFrame = extracted[0].pcmFrames - overlapFrames,
                    frameCount = overlapFrames,
                )
            val right = readPcm16(extracted[1].file, firstFrame = 0L, frameCount = overlapFrames)
            val alignment = bestAlignment(left, right)
            val lagMs = alignment.lagFrames * 1_000.0 / OutputSampleRate
            val line =
                "profile=${profile.name} windows=${plans.joinToString { "${it.uploadStartMs}-${it.uploadEndMs}" }} " +
                    "lagFrames=${alignment.lagFrames} lagMs=${"%.3f".format(lagMs)} " +
                    "correlation=${"%.8f".format(alignment.correlation)}"
            Log.i(Tag, line)
            report += line
            assertTrue("Overlap correlation is too low for $profile: ${alignment.correlation}", alignment.correlation >= 0.90)
            assertTrue("Overlap drift is too large for $profile: ${alignment.lagFrames} frames", abs(alignment.lagFrames) <= 800)
        }

        outputDirectory.resolve("report.txt").writeText(report.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun readPcm16(
        file: File,
        firstFrame: Long,
        frameCount: Long,
    ): ShortArray {
        require(firstFrame >= 0L && frameCount in 1..Int.MAX_VALUE.toLong())
        val bytes = ByteArray(Math.multiplyExact(frameCount.toInt(), BytesPerFrame))
        RandomAccessFile(file, "r").use { input ->
            input.seek(WavHeaderBytes + firstFrame * BytesPerFrame)
            input.readFully(bytes)
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(frameCount.toInt()) { buffer.short }
    }

    private fun bestAlignment(
        left: ShortArray,
        right: ShortArray,
    ): Alignment {
        require(left.size == right.size)
        var coarse = Alignment(0, Double.NEGATIVE_INFINITY)
        for (lag in -MaxLagFrames..MaxLagFrames step CoarseLagStep) {
            val correlation = correlation(left, right, lag, CoarseSampleStep)
            if (correlation > coarse.correlation) coarse = Alignment(lag, correlation)
        }
        var fine = coarse
        for (lag in coarse.lagFrames - CoarseLagStep..coarse.lagFrames + CoarseLagStep) {
            if (abs(lag) > MaxLagFrames) continue
            val correlation = correlation(left, right, lag, 1)
            if (correlation > fine.correlation) fine = Alignment(lag, correlation)
        }
        return fine
    }

    private fun correlation(
        left: ShortArray,
        right: ShortArray,
        lag: Int,
        step: Int,
    ): Double {
        val leftStart = maxOf(lag, 0)
        val rightStart = maxOf(-lag, 0)
        val count = left.size - abs(lag)
        var dot = 0.0
        var leftEnergy = 0.0
        var rightEnergy = 0.0
        var offset = 0
        while (offset < count) {
            val leftValue = left[leftStart + offset].toDouble()
            val rightValue = right[rightStart + offset].toDouble()
            dot += leftValue * rightValue
            leftEnergy += leftValue * leftValue
            rightEnergy += rightValue * rightValue
            offset += step
        }
        val denominator = sqrt(leftEnergy * rightEnergy)
        return if (denominator > 0.0) dot / denominator else 0.0
    }

    private fun File.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(readBytes())
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class Alignment(
        val lagFrames: Int,
        val correlation: Double,
    )

    companion object {
        private const val Tag = "VortexCueDeviceAsr"
        private const val MediaDurationMs = 330_000L
        private const val OutputSampleRate = 16_000
        private const val WavHeaderBytes = 44L
        private const val BytesPerFrame = 2
        private const val MaxLagFrames = 1_600
        private const val CoarseLagStep = 4
        private const val CoarseSampleStep = 8
    }
}
