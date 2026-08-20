package io.github.zvensmoluya.vortexcue.text

import android.app.Application
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleCue
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class TextArtifactStoreTest {
    private lateinit var root: File
    private lateinit var store: PrivateTextArtifactStore

    @Before
    fun setUp() {
        val context: Application = RuntimeEnvironment.getApplication()
        root = File(context.noBackupFilesDir, PrivateTextArtifactStore.RootDirectory)
        root.deleteRecursively()
        store = PrivateTextArtifactStore(context)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `publish is idempotent and inspected from the owned content addressed path`() = runBlocking {
        val timeline =
            CanonicalTextNormalizer.normalizeImported(
                listOf(SubtitleCue(1_000L, 2_000L, "owned")),
                mediaDurationMs = null,
            )

        val first = store.publish(TextArtifactKind.ImportedTimeline, TextArtifactPayload.Timeline(timeline))
        val second = store.publish(TextArtifactKind.ImportedTimeline, TextArtifactPayload.Timeline(timeline))
        val file = store.listFiles().single()
        val inspected = store.inspectPublished(first.artifactId, TextArtifactKind.ImportedTimeline)

        assertEquals(first, second)
        assertEquals(first, inspected.metadata)
        assertEquals(timeline, (inspected.decoded.payload as TextArtifactPayload.Timeline).value)
        assertTrue(file.isPublished)
        assertEquals(first.artifactId.take(2), file.file.parentFile?.name)
        assertEquals(TextArtifactKind.ImportedTimeline.directoryName, file.file.parentFile?.parentFile?.name)
    }

    @Test
    fun `integrity read rejects a damaged published file`() = runBlocking {
        val timeline =
            CanonicalTextNormalizer.normalizeImported(
                listOf(SubtitleCue(0L, 1_000L, "damage")),
                mediaDurationMs = null,
            )
        val metadata = store.publish(TextArtifactKind.ImportedTimeline, TextArtifactPayload.Timeline(timeline))
        store.listFiles().single().file.appendBytes(byteArrayOf(0))

        assertThrows(IOException::class.java) { runBlocking { store.read(metadata) } }
        assertThrows(IOException::class.java) {
            runBlocking { store.inspectPublished(metadata.artifactId, TextArtifactKind.ImportedTimeline) }
        }
        Unit
    }
}
