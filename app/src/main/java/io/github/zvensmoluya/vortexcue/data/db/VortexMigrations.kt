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

internal val MIGRATION_9_10 =
    object : Migration(9, 10) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `playlist` ADD COLUMN `system_role` TEXT")
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_system_role` ON `playlist` (`system_role`)",
            )
            connection.execSQL(
                "UPDATE `playlist` SET `system_role` = 'DefaultFavorite' " +
                    "WHERE `playlist_id` = (SELECT `playlist_id` FROM `playlist` " +
                    "WHERE `name` = '默认收藏' COLLATE NOCASE LIMIT 1)",
            )
            connection.execSQL(
                """
                INSERT INTO `playlist` (
                    `playlist_id`, `name`, `sort_position`, `created_at_ms`, `updated_at_ms`,
                    `last_played_at_ms`, `system_role`
                )
                SELECT 'playlist:default-favorite', '默认收藏', -1, 0, 0, NULL, 'DefaultFavorite'
                WHERE NOT EXISTS (SELECT 1 FROM `playlist` WHERE `system_role` = 'DefaultFavorite')
                """.trimIndent(),
            )
            connection.execSQL(
                """
                INSERT OR IGNORE INTO `playlist_item` (`playlist_id`, `content_id`, `sort_position`, `added_at_ms`)
                SELECT p.`playlist_id`, d.`content_id`,
                    (
                        SELECT COUNT(*) FROM `content_disposition` before_d
                        WHERE before_d.`is_collected` = 1
                          AND NOT EXISTS (
                              SELECT 1 FROM `playlist_item` before_i
                              WHERE before_i.`content_id` = before_d.`content_id`
                          )
                          AND (
                              before_d.`decided_at_ms` > d.`decided_at_ms`
                              OR (before_d.`decided_at_ms` = d.`decided_at_ms`
                                  AND before_d.`content_id` < d.`content_id`)
                          )
                    ),
                    d.`decided_at_ms`
                FROM `content_disposition` d
                CROSS JOIN `playlist` p
                WHERE p.`system_role` = 'DefaultFavorite'
                  AND d.`is_collected` = 1
                  AND NOT EXISTS (
                      SELECT 1 FROM `playlist_item` i WHERE i.`content_id` = d.`content_id`
                  )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                DELETE FROM `content`
                WHERE `content_id` IN (
                    SELECT c.`content_id`
                    FROM `content` c
                    JOIN `active_media_locator` a ON a.`content_id` = c.`content_id`
                    JOIN `media_locator` l ON l.`locator_id` = a.`locator_id`
                    LEFT JOIN `content_disposition` d ON d.`content_id` = c.`content_id`
                    WHERE d.`content_id` IS NULL
                      AND l.`kind` = 'Video'
                      AND l.`origin` IN ('MediaStore', 'DocumentTree')
                      AND NOT EXISTS (SELECT 1 FROM `playlist_item` i WHERE i.`content_id` = c.`content_id`)
                      AND NOT EXISTS (SELECT 1 FROM `current_queue_entry` q WHERE q.`content_id` = c.`content_id`)
                      AND NOT EXISTS (SELECT 1 FROM `recent_entry` r WHERE r.`content_id` = c.`content_id`)
                      AND NOT EXISTS (SELECT 1 FROM `current_draft` x WHERE x.`content_id` = c.`content_id`)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `import_source_preference` (
                    `source_key` TEXT NOT NULL,
                    `last_imported_at_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`source_key`)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `import_source_target` (
                    `source_key` TEXT NOT NULL,
                    `playlist_id` TEXT NOT NULL,
                    PRIMARY KEY(`source_key`, `playlist_id`),
                    FOREIGN KEY(`source_key`) REFERENCES `import_source_preference`(`source_key`)
                        ON UPDATE CASCADE ON DELETE CASCADE,
                    FOREIGN KEY(`playlist_id`) REFERENCES `playlist`(`playlist_id`)
                        ON UPDATE CASCADE ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_import_source_target_playlist_id` " +
                    "ON `import_source_target` (`playlist_id`)",
            )
        }
    }

internal val MIGRATION_10_11 =
    object : Migration(10, 11) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `work` (
                    `work_id` TEXT NOT NULL,
                    `source_key` TEXT NOT NULL,
                    `release_code` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `manifest_fingerprint` TEXT NOT NULL,
                    `profile_label` TEXT NOT NULL,
                    `created_at_ms` INTEGER NOT NULL,
                    `updated_at_ms` INTEGER NOT NULL,
                    `last_played_at_ms` INTEGER,
                    PRIMARY KEY(`work_id`)
                )
                """.trimIndent(),
            )
            connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_work_source_key` ON `work` (`source_key`)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_work_last_played_at_ms` ON `work` (`last_played_at_ms`)")
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `work_part` (
                    `work_id` TEXT NOT NULL,
                    `content_id` TEXT NOT NULL,
                    `section_name` TEXT NOT NULL,
                    `variant_key` TEXT NOT NULL,
                    `sort_position` INTEGER NOT NULL,
                    `is_included` INTEGER NOT NULL,
                    PRIMARY KEY(`work_id`, `content_id`),
                    FOREIGN KEY(`work_id`) REFERENCES `work`(`work_id`) ON UPDATE CASCADE ON DELETE CASCADE,
                    FOREIGN KEY(`content_id`) REFERENCES `content`(`content_id`) ON UPDATE CASCADE ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_work_part_content_id` ON `work_part` (`content_id`)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_work_part_work_id_sort_position` ON `work_part` (`work_id`, `sort_position`)")
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `work_progress` (
                    `work_id` TEXT NOT NULL,
                    `content_id` TEXT,
                    `position_ms` INTEGER NOT NULL,
                    `updated_at_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`work_id`),
                    FOREIGN KEY(`work_id`) REFERENCES `work`(`work_id`) ON UPDATE CASCADE ON DELETE CASCADE,
                    FOREIGN KEY(`content_id`) REFERENCES `content`(`content_id`) ON UPDATE CASCADE ON DELETE SET NULL
                )
                """.trimIndent(),
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_work_progress_content_id` ON `work_progress` (`content_id`)")
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `playlist_entry` (
                    `entry_id` TEXT NOT NULL,
                    `playlist_id` TEXT NOT NULL,
                    `content_id` TEXT,
                    `work_id` TEXT,
                    `sort_position` INTEGER NOT NULL,
                    `added_at_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`entry_id`),
                    FOREIGN KEY(`playlist_id`) REFERENCES `playlist`(`playlist_id`) ON UPDATE CASCADE ON DELETE CASCADE,
                    FOREIGN KEY(`content_id`) REFERENCES `content`(`content_id`) ON UPDATE CASCADE ON DELETE CASCADE,
                    FOREIGN KEY(`work_id`) REFERENCES `work`(`work_id`) ON UPDATE CASCADE ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_entry_content_id` ON `playlist_entry` (`content_id`)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_entry_work_id` ON `playlist_entry` (`work_id`)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_entry_playlist_id_sort_position` ON `playlist_entry` (`playlist_id`, `sort_position`)")
            connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_entry_playlist_id_content_id` ON `playlist_entry` (`playlist_id`, `content_id`)")
            connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_entry_playlist_id_work_id` ON `playlist_entry` (`playlist_id`, `work_id`)")
            connection.execSQL(
                """
                INSERT INTO `playlist_entry` (`entry_id`, `playlist_id`, `content_id`, `work_id`, `sort_position`, `added_at_ms`)
                SELECT 'content:' || `playlist_id` || ':' || `content_id`, `playlist_id`, `content_id`, NULL,
                    `sort_position`, `added_at_ms`
                FROM `playlist_item`
                """.trimIndent(),
            )
            connection.execSQL("DROP TABLE `playlist_item`")
            connection.execSQL("ALTER TABLE `current_queue_entry` ADD COLUMN `origin_work_id` TEXT")
            connection.execSQL("ALTER TABLE `current_queue_entry` ADD COLUMN `origin_playlist_entry_id` TEXT")
        }
    }

internal val MIGRATION_11_12 =
    object : Migration(11, 12) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE `sync_text_scheme` ADD COLUMN `translation_reasoning_level` TEXT NOT NULL DEFAULT 'Off'",
            )
            connection.execSQL(
                "ALTER TABLE `generation_attempt` ADD COLUMN `translation_reasoning_level` TEXT NOT NULL DEFAULT 'Off'",
            )
        }
    }

internal val MIGRATION_12_13 =
    object : Migration(12, 13) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `generation_runtime_status` (
                    `runtime_id` TEXT NOT NULL,
                    `task_id` TEXT NOT NULL,
                    `work_unit_kind` TEXT,
                    `work_unit_id` TEXT,
                    `phase` TEXT NOT NULL,
                    `observed_item_count` INTEGER,
                    `expected_item_count` INTEGER,
                    `operation_ordinal` INTEGER,
                    `retry_ordinal` INTEGER,
                    `phase_started_at_ms` INTEGER NOT NULL,
                    `updated_at_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`runtime_id`),
                    FOREIGN KEY(`task_id`) REFERENCES `generation_task`(`task_id`) ON UPDATE CASCADE ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_generation_runtime_status_task_id` ON `generation_runtime_status` (`task_id`)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_generation_runtime_status_work_unit_id` ON `generation_runtime_status` (`work_unit_id`)",
            )
        }
    }
