package io.github.zvensmoluya.vortexcue.data.db

import android.content.Context
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
        RecentEntryEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        HomeShortcutEntity::class,
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
    version = 2,
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
