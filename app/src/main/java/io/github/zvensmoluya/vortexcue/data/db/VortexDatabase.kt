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
        ContentFolderEntity::class,
        ContentFolderMembershipEntity::class,
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
        TranslationBatchEntity::class,
        RequestAttemptEntity::class,
        GeneratedTextSlotEntity::class,
        GeneratedTextResultEntity::class,
        SyncTextRunnerLeaseEntity::class,
        SyncTextQueueControlEntity::class,
    ],
    // The new content model intentionally allows a destructive upgrade. Original
    // media remains in user storage; only app-owned indexes are rebuilt.
    version = 4,
    autoMigrations = [AutoMigration(from = 2, to = 3)],
    exportSchema = true,
)
abstract class VortexDatabase : RoomDatabase() {
    abstract fun contentDao(): ContentDao

    abstract fun playbackDao(): PlaybackDao

    abstract fun syncTextDao(): SyncTextDao

    companion object {
        const val DatabaseName = "vortexcue.db"

        fun create(context: Context): VortexDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                VortexDatabase::class.java,
                DatabaseName,
            ).setDriver(AndroidSQLiteDriver())
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
