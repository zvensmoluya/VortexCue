package io.github.zvensmoluya.vortexcue.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryMigrationTest {
    @Test
    fun `legacy manual items remain recent and collected`() {
        val migration =
            resolveLibraryMigration(
                catalogUriValues = listOf("content://catalog/1"),
                recentUriValues = listOf("content://recent/1"),
                includedUriValues = listOf("content://catalog/1"),
                legacyManualUriValues =
                    listOf("content://legacy/1", "content://catalog/1"),
            )

        assertEquals(
            listOf("content://catalog/1", "content://legacy/1"),
            migration.catalogUriValues,
        )
        assertEquals(
            listOf("content://recent/1", "content://legacy/1", "content://catalog/1"),
            migration.recentUriValues,
        )
        assertEquals(
            listOf("content://catalog/1", "content://legacy/1"),
            migration.includedUriValues,
        )
    }

    @Test
    fun `current catalog state is unchanged without legacy items`() {
        val migration =
            resolveLibraryMigration(
                catalogUriValues = listOf("content://catalog/1"),
                recentUriValues = listOf("content://recent/1"),
                includedUriValues = listOf("content://catalog/1"),
                legacyManualUriValues = emptyList(),
            )

        assertEquals(listOf("content://catalog/1"), migration.catalogUriValues)
        assertEquals(listOf("content://recent/1"), migration.recentUriValues)
        assertEquals(listOf("content://catalog/1"), migration.includedUriValues)
    }
}
