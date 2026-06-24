package org.koharu.miyo.local.data.index

import android.content.Context
import androidx.core.content.edit
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koharu.miyo.core.db.MangaDatabase
import org.koharu.miyo.core.db.entity.toManga
import org.koharu.miyo.core.parser.MangaDataRepository
import org.koharu.miyo.core.util.ext.printStackTraceDebug
import org.koharu.miyo.local.data.LocalMangaRepository
import org.koharu.miyo.local.data.input.LocalMangaParser
import org.koharu.miyo.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class LocalMangaIndex @Inject constructor(
	private val mangaDataRepository: MangaDataRepository,
	private val db: MangaDatabase,
	@ApplicationContext context: Context,
	private val localMangaRepositoryProvider: Provider<LocalMangaRepository>,
) : FlowCollector<LocalManga?> {

	private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
	private val mutex = Mutex()
	private var lastMissRefreshAt = 0L

	private var currentVersion: Int
		get() = prefs.getInt(KEY_VERSION, 0)
		set(value) = prefs.edit { putInt(KEY_VERSION, value) }

	override suspend fun emit(value: LocalManga?) {
		if (value != null) {
			put(value)
		}
	}

	suspend fun update() = mutex.withLock {
		rebuildIndexLocked()
		lastMissRefreshAt = System.currentTimeMillis()
	}

	private suspend fun rebuildIndexLocked() {
		db.withTransaction {
			val dao = db.getLocalMangaIndexDao()
			dao.clear()
			localMangaRepositoryProvider.get()
				.getRawListAsFlow()
				.collect { upsert(it) }
		}
		currentVersion = VERSION
	}

	suspend fun updateIfRequired() {
		if (isUpdateRequired()) {
			update()
		}
	}

	suspend fun get(mangaId: Long, withDetails: Boolean): LocalManga? {
		updateIfRequired()
		var path = db.getLocalMangaIndexDao().findPath(mangaId)
		if (path == null && mutex.isLocked) { // wait for updating complete
			path = mutex.withLock { db.getLocalMangaIndexDao().findPath(mangaId) }
		}
		if (path == null) {
			return null
		}
		val file = File(path)
		if (!file.exists()) {
			db.getLocalMangaIndexDao().delete(mangaId)
			return null
		}
		if (!withDetails) {
			mangaDataRepository.findMangaById(mangaId, withChapters = false)?.let { cachedManga ->
				return LocalManga(cachedManga, file)
			}
		}
		val localManga = runCatchingCancellable {
			LocalMangaParser(file).getMangaIfHasChapters(withDetails)
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrNull()
		if (localManga == null) {
			db.getLocalMangaIndexDao().delete(mangaId)
		}
		return localManga
	}

	suspend fun getCachedList(): ArrayList<LocalManga> {
		updateIfRequired()
		val dao = db.getLocalMangaIndexDao()
		val result = ArrayList<LocalManga>()
		for (storedManga in dao.findIndexedManga()) {
			val manga = storedManga.toManga()
			val path = dao.findPath(manga.id)
			if (path != null && hasExistingPath(manga.id)) {
				result.add(LocalManga(manga, File(path)))
			}
		}
		return result
	}

	suspend operator fun contains(mangaId: Long): Boolean {
		updateIfRequired()
		if (hasExistingPath(mangaId)) {
			return true
		}
		refreshAfterMiss()
		return hasExistingPath(mangaId)
	}

	suspend fun put(manga: LocalManga) {
		val indexedManga = manga.validatedForIndex()
		mutex.withLock {
			if (indexedManga == null) {
				db.getLocalMangaIndexDao().delete(manga.manga.id)
				return@withLock
			}
			db.withTransaction {
				upsert(indexedManga)
			}
		}
	}

	suspend fun replaceWith(manga: Collection<LocalManga>) = mutex.withLock {
		db.withTransaction {
			val dao = db.getLocalMangaIndexDao()
			dao.clear()
			manga.forEach { upsert(it) }
		}
		currentVersion = VERSION
		lastMissRefreshAt = System.currentTimeMillis()
	}


	suspend fun delete(mangaId: Long) {
		db.getLocalMangaIndexDao().delete(mangaId)
	}

	suspend fun getAvailableTags(skipNsfw: Boolean): List<String> {
		updateIfRequired()
		val dao = db.getLocalMangaIndexDao()
		return if (skipNsfw) {
			dao.findTags(isNsfw = false)
		} else {
			dao.findTags()
		}
	}

	private suspend fun refreshAfterMiss() {
		val now = System.currentTimeMillis()
		if (now - lastMissRefreshAt < MISS_REFRESH_INTERVAL) {
			return
		}
		mutex.withLock {
			val lockedNow = System.currentTimeMillis()
			if (lockedNow - lastMissRefreshAt >= MISS_REFRESH_INTERVAL) {
				rebuildIndexLocked()
				lastMissRefreshAt = lockedNow
			}
		}
	}

	private suspend fun upsert(manga: LocalManga) {
		mangaDataRepository.storeManga(manga.manga, replaceExisting = true)
		db.getLocalMangaIndexDao().upsert(manga.toEntity())
	}

	private suspend fun LocalManga.validatedForIndex(): LocalManga? {
		return runCatchingCancellable {
			LocalMangaParser(file).getMangaIfHasChapters(withDetails = false)
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrNull()
	}

	private suspend fun hasExistingPath(mangaId: Long): Boolean {
		val dao = db.getLocalMangaIndexDao()
		val path = dao.findPath(mangaId) ?: return false
		val file = File(path)
		if (file.exists()) {
			return true
		}
		dao.delete(mangaId)
		return false
	}

	private fun LocalManga.toEntity() = LocalMangaIndexEntity(
		mangaId = manga.id,
		path = file.path,
	)

	private fun isUpdateRequired() = currentVersion < VERSION

	companion object {

		private const val PREF_NAME = "_local_index"
		private const val KEY_VERSION = "ver"
		private const val VERSION = 3
		private const val MISS_REFRESH_INTERVAL = 30_000L
	}
}
