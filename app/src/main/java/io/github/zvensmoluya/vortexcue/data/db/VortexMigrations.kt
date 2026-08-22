package io.github.zvensmoluya.vortexcue.data.db

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val MIGRATION_8_9 =
    object : Migration(8, 9) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `playlist` (
                    `playlist_id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `sort_position` INTEGER NOT NULL,
                    `created_at_ms` INTEGER NOT NULL,
                    `updated_at_ms` INTEGER NOT NULL,
                    `last_played_at_ms` INTEGER,
                    PRIMARY KEY(`playlist_id`)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_name` ON `playlist` (`name`)",
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `playlist_item` (
                    `playlist_id` TEXT NOT NULL,
                    `content_id` TEXT NOT NULL,
                    `sort_position` INTEGER NOT NULL,
                    `added_at_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`playlist_id`, `content_id`),
                    FOREIGN KEY(`playlist_id`) REFERENCES `playlist`(`playlist_id`) ON UPDATE CASCADE ON DELETE CASCADE,
                    FOREIGN KEY(`content_id`) REFERENCES `content`(`content_id`) ON UPDATE CASCADE ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_playlist_item_content_id` ON `playlist_item` (`content_id`)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_playlist_item_playlist_id_sort_position` " +
                    "ON `playlist_item` (`playlist_id`, `sort_position`)",
            )
            connection.execSQL(
                """
                INSERT INTO `playlist` (
                    `playlist_id`, `name`, `sort_position`, `created_at_ms`, `updated_at_ms`, `last_played_at_ms`
                )
                SELECT f.`folder_id`, f.`name`,
                    (
                        SELECT COUNT(*) FROM `content_folder` before_f
                        WHERE before_f.`name` COLLATE NOCASE < f.`name` COLLATE NOCASE
                           OR (before_f.`name` COLLATE NOCASE = f.`name` COLLATE NOCASE
                               AND before_f.`folder_id` < f.`folder_id`)
                    ),
                    f.`created_at_ms`, f.`updated_at_ms`, NULL
                FROM `content_folder` f
                """.trimIndent(),
            )
            connection.execSQL(
                """
                INSERT INTO `playlist_item` (`playlist_id`, `content_id`, `sort_position`, `added_at_ms`)
                SELECT m.`folder_id`, m.`content_id`,
                    (
                        SELECT COUNT(*) FROM `content_folder_membership` before_m
                        WHERE before_m.`folder_id` = m.`folder_id`
                          AND (before_m.`moved_at_ms` < m.`moved_at_ms`
                               OR (before_m.`moved_at_ms` = m.`moved_at_ms`
                                   AND before_m.`content_id` < m.`content_id`))
                    ),
                    m.`moved_at_ms`
                FROM `content_folder_membership` m
                """.trimIndent(),
            )
            connection.execSQL("DROP TABLE `content_folder_membership`")
            connection.execSQL("DROP TABLE `content_folder`")
        }
    }
