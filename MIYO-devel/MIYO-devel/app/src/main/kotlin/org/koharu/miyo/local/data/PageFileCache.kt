package org.koharu.miyo.local.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance page file cache using direct file storage with LRU eviction.
 * Replaces DiskLruCache for page storage — faster random access, simpler eviction.
 */
class PageFileCache(
    private val directory: File,
    private val maxSize: Long,
) {
    private val fileSystem = FileSystem.SYSTEM

    private val accessTimes = ConcurrentHashMap<String, Long>()
    private val totalSize = AtomicLong(0L)
    private val mutex = Mutex()

    init {
        directory.mkdirs()
        // Scan existing files to compute initial size
        directory.listFiles()?.forEach { file ->
            if (file.name.endsWith(".tmp")) {
                file.delete()
            } else if (file.isFile) {
                totalSize.addAndGet(file.length())
                accessTimes[file.name] = file.lastModified()
            }
        }
    }

    suspend fun get(key: String): File? {
        val safeName = key.toSafeFileName()
        val file = File(directory, safeName)
        if (!file.exists() || !file.isFile) return null
        accessTimes[safeName] = System.currentTimeMillis()
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    suspend fun put(key: String, data: okio.Source, type: String? = null): File = mutex.withLock {
        val safeName = key.toSafeFileName()
        val file = File(directory, safeName)
        val tmpFile = File(directory, "$safeName.tmp")
        val previousSize = if (file.exists()) file.length() else 0L
        try {
            fileSystem.sink(tmpFile.absolutePath.toPath()).buffer().use { it.writeAll(data) }
            if (file.exists()) {
                file.delete()
            }
            check(tmpFile.renameTo(file)) { "Cannot commit cache file: ${file.name}" }
        } catch (e: Throwable) {
            tmpFile.delete()
            throw e
        }
        totalSize.addAndGet(file.length() - previousSize)
        accessTimes[safeName] = System.currentTimeMillis()
        evictIfNeeded()
        file
    }

    suspend fun putBytes(key: String, bytes: ByteArray): File = mutex.withLock {
        val safeName = key.toSafeFileName()
        val file = File(directory, safeName)
        val tmpFile = File(directory, "$safeName.tmp")
        val previousSize = if (file.exists()) file.length() else 0L
        try {
            withContext(Dispatchers.IO) {
                tmpFile.writeBytes(bytes)
            }
            if (file.exists()) {
                file.delete()
            }
            check(tmpFile.renameTo(file)) { "Cannot commit cache file: ${file.name}" }
        } catch (e: Throwable) {
            tmpFile.delete()
            throw e
        }
        totalSize.addAndGet(file.length() - previousSize)
        accessTimes[safeName] = System.currentTimeMillis()
        evictIfNeeded()
        file
    }

    suspend fun clear() = mutex.withLock {
        accessTimes.clear()
        totalSize.set(0L)
        withContext(Dispatchers.IO) {
            directory.listFiles()?.forEach { it.delete() }
        }
    }

    suspend fun remove(key: String) = mutex.withLock {
        val safeName = key.toSafeFileName()
        val file = File(directory, safeName)
        if (file.exists()) {
            val fileSize = file.length()
            val isDeleted = withContext(Dispatchers.IO) {
                file.delete()
            }
            if (isDeleted || !file.exists()) {
                totalSize.addAndGet(-fileSize)
                accessTimes.remove(safeName)
            }
        } else {
            accessTimes.remove(safeName)
        }
    }

    val size: Long get() = totalSize.get()

    private suspend fun evictIfNeeded() {
        while (totalSize.get() > maxSize) {
            val oldest = accessTimes.entries
                .minByOrNull { it.value }
                ?: break
            val file = File(directory, oldest.key)
            if (file.exists()) {
                val fileSize = file.length()
                val isDeleted = withContext(Dispatchers.IO) { file.delete() }
                if (isDeleted || !file.exists()) {
                    totalSize.addAndGet(-fileSize)
                }
            }
            accessTimes.remove(oldest.key)
        }
    }

    companion object {
        private fun String.toSafeFileName(): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(toByteArray())
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
            val suffix = takeLast(48).replace(Regex("[^a-zA-Z0-9._-]"), "_")
            return "${digest}_$suffix"
        }
    }
}
