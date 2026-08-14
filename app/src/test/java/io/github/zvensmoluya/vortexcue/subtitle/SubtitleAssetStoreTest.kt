package io.github.zvensmoluya.vortexcue.subtitle

import android.content.Context
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SubtitleAssetStoreTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val currentDirectory
        get() = File(context.filesDir, "subtitle_assets")

    private val legacyDirectory
        get() = File(context.filesDir, "session_subtitles")

    @Before
    fun setUp() {
        clearFiles(currentDirectory)
        clearFiles(legacyDirectory)
    }

    @After
    fun tearDown() {
        clearFiles(currentDirectory)
        clearFiles(legacyDirectory)
    }

    @Test
    fun pruneKeepsOnlyFilesReferencedByRoom() {
        currentDirectory.mkdirs()
        legacyDirectory.mkdirs()
        val retained = File(currentDirectory, "retained.vtt").apply { writeText("retained") }
        val orphan = File(currentDirectory, "orphan.vtt").apply { writeText("orphan") }
        val legacyOrphan = File(legacyDirectory, "legacy.vtt").apply { writeText("legacy") }

        SubtitleAssetStore(context).pruneExcept(setOf(retained.name))

        assertTrue(retained.isFile)
        assertFalse(orphan.exists())
        assertFalse(legacyOrphan.exists())
    }

    private fun clearFiles(directory: File) {
        directory.listFiles().orEmpty().filter(File::isFile).forEach(File::delete)
    }
}
