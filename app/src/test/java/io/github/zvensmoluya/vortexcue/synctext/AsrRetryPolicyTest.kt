package io.github.zvensmoluya.vortexcue.synctext

import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.runBlocking
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrRetryPolicyTest {
    @Test
    fun `rate limits retry the same window after server delay without splitting`() = runBlocking {
        val engine = engine()
        val failure =
            engine.classifyFailure(
                exchange(status = 429, retryAfter = "12"),
                apiKey = "secret",
            )

        assertTrue(failure.retryable)
        assertFalse(failure.shouldSplit)
        assertEquals(12_000L, AsrRetryPolicy.delayMs(failure, failedAttemptIndex = 0))
        assertFalse(AsrRetryPolicy.shouldSplit(failure, rootWindow()))
    }

    @Test
    fun `temporary server failures back off without splitting`() = runBlocking {
        val failure = engine().classifyFailure(exchange(status = 503), apiKey = "secret")

        assertTrue(failure.retryable)
        assertFalse(failure.shouldSplit)
        assertEquals(500L, AsrRetryPolicy.delayMs(failure, failedAttemptIndex = 0))
        assertEquals(1_000L, AsrRetryPolicy.delayMs(failure, failedAttemptIndex = 1))
    }

    @Test
    fun `transport failures retry without splitting`() {
        val failure = asrTransportFailure(IOException("offline"))

        assertTrue(failure.retryable)
        assertFalse(failure.shouldSplit)
    }

    @Test
    fun `capacity failures split only a root window`() = runBlocking {
        val failure = engine().classifyFailure(exchange(status = 413), apiKey = "secret")
        val root = rootWindow()
        val child = root.copy(windowId = "child", parentWindowId = root.windowId)

        assertFalse(failure.retryable)
        assertTrue(failure.shouldSplit)
        assertTrue(AsrRetryPolicy.shouldSplit(failure, root))
        assertFalse(AsrRetryPolicy.shouldSplit(failure, child))
    }

    @Test
    fun `retry after accepts dates and caps excessive waits`() {
        val nowMs = Instant.parse("2026-08-19T01:00:00Z").toEpochMilli()
        val date =
            DateTimeFormatter.RFC_1123_DATE_TIME.format(
                Instant.ofEpochMilli(nowMs + 15_000L).atZone(ZoneOffset.UTC),
            )

        assertEquals(15_000L, parseRetryAfterMs(date, nowMs))
        assertEquals(60_000L, parseRetryAfterMs("3600", nowMs))
        assertNull(parseRetryAfterMs("later", nowMs))
    }

    private fun engine() =
        OpenRouterAsrEngine(
            executor = BoundedHttpExecutor(okhttp3.OkHttpClient()),
            budget = EncodedAudioBudget(),
        )

    private fun exchange(
        status: Int,
        retryAfter: String? = null,
    ) =
        HttpExchange(
            status = status,
            headers = retryAfter?.let { headersOf("Retry-After", it) } ?: headersOf(),
            body = """{"error":{"message":"failed"}}""".toByteArray(),
            endpoint = "https://openrouter.ai/api/v1/audio/transcriptions",
            requestBytes = 100L,
        )

    private fun rootWindow() =
        AsrWindowPlan(
            windowId = "root",
            ordinal = 0,
            ownedStartMs = 0L,
            ownedEndMs = 300_000L,
            uploadStartMs = 0L,
            uploadEndMs = 300_000L,
            pcmStartFrame = 0L,
            pcmEndFrame = 4_800_000L,
            cutReason = "fixed_v2",
            isLast = true,
        )
}
