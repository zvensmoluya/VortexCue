package io.github.zvensmoluya.vortexcue.synctext

import java.net.InetAddress
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncTextHttpClientTest {
    @Test
    fun `regular endpoints receive app identity without OpenRouter attribution`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse())

            SyncTextHttpClient("1.2.3 beta").client.newCall(
                Request.Builder().url(server.url("/translate")).build(),
            ).execute().use { response ->
                assertEquals(200, response.code)
            }

            val request = server.takeRequest()
            assertEquals("VortexCue/1.2.3_beta (Android)", request.headers["User-Agent"])
            assertNull(request.headers["HTTP-Referer"])
            assertNull(request.headers["X-OpenRouter-Title"])
        }
    }

    @Test
    fun `OpenRouter receives app attribution`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse())
            val client =
                SyncTextHttpClient("2.0.0").client.newBuilder()
                    .dns(
                        Dns { hostname ->
                            if (hostname == "openrouter.ai") {
                                listOf(InetAddress.getLoopbackAddress())
                            } else {
                                Dns.SYSTEM.lookup(hostname)
                            }
                        },
                    ).build()
            val url = "http://openrouter.ai:${server.port}/api/v1/audio/transcriptions"

            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                assertEquals(200, response.code)
            }

            val request = server.takeRequest()
            assertEquals(ClientIdentityInterceptor.VortexCueProjectUrl, request.headers["HTTP-Referer"])
            assertEquals(ClientIdentityInterceptor.VortexCueAppTitle, request.headers["X-OpenRouter-Title"])
        }
    }

    @Test
    fun `request identity overrides the app default`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse())
            val request =
                Request.Builder()
                    .url(server.url("/translate"))
                    .header("User-Agent", "PrivateGatewayClient/4.1")
                    .build()

            SyncTextHttpClient("2.0.0").client.newCall(request).execute().close()

            assertEquals("PrivateGatewayClient/4.1", server.takeRequest().headers["User-Agent"])
        }
    }
}
