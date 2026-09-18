package com.example.trueframe.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProxyCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectDao: ProjectDao,
) {
    /**
     * Reconciles the proxy cache directory with the database.
     * Deletes any proxy files that do not have a corresponding entry in the database.
     * Should be called on startup.
     */
    suspend fun reconcileCache() = withContext(Dispatchers.IO) {
        val cacheDir = getCacheDir()
        if (!cacheDir.exists()) return@withContext

        val cachedFiles = cacheDir.listFiles() ?: return@withContext
        val projects = projectDao.getAll()
        val validProjectIds = projects.map { it.id }.toSet()

        for (file in cachedFiles) {
            val name = file.name
            if (name.startsWith("proxy_") && name.endsWith(".mp4")) {
                val projectIdStr = name.removePrefix("proxy_").removeSuffix(".mp4")
                val projectId = projectIdStr.toLongOrNull()
                if (projectId == null || projectId !in validProjectIds) {
                    file.delete()
                }
            } else {
                file.delete()
            }
        }
    }

    /**
     * Gets the directory used for storing proxy files.
     */
    fun getCacheDir(): File {
        val dir = File(context.noBackupFilesDir, "proxy_videos")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Generates a new file path for a proxy video.
     */
    fun generateProxyPath(projectId: Long): String {
        return "file://" + File(getCacheDir(), "proxy_$projectId.mp4").absolutePath
    }
}
