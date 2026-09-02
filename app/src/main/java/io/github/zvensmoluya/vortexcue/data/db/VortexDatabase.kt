package io.github.zvensmoluya.vortexcue.data.db

import android.content.Context
import androidx.room3.AutoMigration
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver

@Database(
    entities = [
        ContentEntity::class,
        ContentIdentityEntity::class,
        ContentRedirectEntity::class,
        SourceEntity::class,
        MediaLocatorEntity::class,
        ActiveMediaLocatorEntity::class,
        TimedTextTrackEntity::class,
        TimedTextTrackRoleEntity::class,
        TextSelectionEntity::class,
        ContentDispositionEntity::class,
        WorkEntity::class,
        WorkPartEntity::class,
        WorkProgressEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        ImportSourcePreferenceEntity::class,
        ImportSourceTargetEntity::class,
        RecentEntryEntity::class,
        CurrentDraftEntity::class,
        CurrentPlaybackSessionEntity::class,
        CurrentQueueEntryEntity::class,
        CredentialMetadataEntity::class,
        SyncTextSchemeEntity::class,
        GenerationTaskEntity::class,
        GenerationTaskSnapshotEntity::class,
        GenerationAttemptEntity::class,
        AsrWindowEntity::class,
        SourcePreparationEntity::class,
        TranslationBatchEntity::class,
        GenerationRuntimeStatusEntity::class,
        RequestAttemptEntity::class,
        SyncTextDiagnosticEventEntity::class,
        GeneratedTextSlotEntity::class,
        GeneratedTextResultEntity::class,
        SyncTextRunnerLeaseEntity::class,
        SyncTextQueueControlEntity::class,
        TextArtifactEntity::class,
        TimelineRevisionEntity::class,
        TimelineRevisionArtifactEntity::class,
        DiscoveredTextCandidateEntity::class,
        TextImportOperationEntity::class,
    ],
    // The new content model intentionally allows a destructive upgrade. Original
    // media remains in user storage; only app-owned indexes are rebuilt.
    version = 16,
    autoMigrations = [
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 7, to = 8),
    ],
    exportSchema = true,
)
abstract class VortexDatabase : RoomDatabase() {
    abstract fun contentDao(): ContentDao

    abstract fun playbackDao(): PlaybackDao

    abstract fun workDao(): WorkDao

    abstract fun syncTextDao(): SyncTextDao

    abstract fun textStorageDao(): TextStorageDao

    companion object {
        const val DatabaseName = "vortexcue.db"

        fun create(context: Context): VortexDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                VortexDatabase::class.java,
                DatabaseName,
            ).setDriver(AndroidSQLiteDriver())
                .addMigrations(
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
