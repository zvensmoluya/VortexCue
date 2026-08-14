package io.github.zvensmoluya.vortexcue.app

import android.app.Application
import android.content.Context
import io.github.zvensmoluya.vortexcue.data.ContentRepository
import io.github.zvensmoluya.vortexcue.data.PlaybackStateRepository
import io.github.zvensmoluya.vortexcue.data.SettingsStore
import io.github.zvensmoluya.vortexcue.data.db.VortexDatabase
import io.github.zvensmoluya.vortexcue.subtitle.SubtitleAssetStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VortexCueApplication : Application() {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.initialize()
    }
}

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: VortexDatabase = VortexDatabase.create(appContext)
    val contentRepository = ContentRepository(database)
    val playbackStateRepository = PlaybackStateRepository(database, contentRepository)
    val settingsStore = SettingsStore(appContext)
    val subtitleAssetStore = SubtitleAssetStore(appContext)

    fun initialize() {
        scope.launch {
            playbackStateRepository.initialize()
            settingsStore.initializeFromLegacyAndCleanDomainStores()
            val retained = database.contentDao().referencedSubtitleCacheFiles().toSet()
            withContext(Dispatchers.IO) { subtitleAssetStore.pruneExcept(retained) }
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as VortexCueApplication).container
