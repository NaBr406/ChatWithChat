package cn.nabr.chatwithchat.data.visual

import cn.nabr.chatwithchat.data.database.dao.VisualAssetDao
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VisualAssetGarbageCollector @Inject constructor(
    private val dao: VisualAssetDao,
    private val repository: VisualAssetRepository,
    private val store: VisualAssetStore
) {
    suspend fun collect() {
        repository.deleteUnreferencedAssets()

        val knownPaths = dao.getAllAssets()
            .flatMap { asset -> listOfNotNull(asset.originalPath, asset.compactedPath) }
            .toSet()
        store.listFiles()
            .filterNot { file -> file.absolutePath in knownPaths }
            .forEach { file -> file.delete() }
    }
}
