package io.github.zvensmoluya.vortexcue.ui.synctext

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTextUiValidationTest {
    @Test
    fun acceptsOnlyHttpsEndpointsWithoutCredentialsOrFragments() {
        assertTrue(isHttpsEndpoint("https://openrouter.ai/api/v1/audio/transcriptions"))
        assertTrue(isHttpsEndpoint("https://gateway.example/v1/models/{model}:generateContent"))
        assertFalse(isHttpsEndpoint("http://gateway.example/v1"))
        assertFalse(isHttpsEndpoint("https://user:secret@gateway.example/v1"))
        assertFalse(isHttpsEndpoint("https://gateway.example/v1#fragment"))
        assertFalse(isHttpsEndpoint("not a url"))
    }
}
