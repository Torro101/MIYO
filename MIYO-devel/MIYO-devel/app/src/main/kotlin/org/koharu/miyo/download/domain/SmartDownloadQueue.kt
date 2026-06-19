package org.koharu.miyo.download.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.parsers.model.Manga
import java.util.ArrayDeque
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmartDownloadQueue @Inject constructor() {

	private val queue = ArrayList<QueueEntry>()
	private val queueComparator = compareBy<QueueEntry> {
		it.priority.rank
	}.thenBy { it.addedAtMs }

	private val _currentReading = MutableStateFlow<Long?>(null)
	val currentReading: Flow<Long?> = _currentReading.asStateFlow()

	private val mutex = Mutex()

	fun setCurrentReading(mangaId: Long?) {
		_currentReading.value = mangaId
	}

	fun priorityFor(manga: Manga): Priority {
		return if (_currentReading.value == manga.id) Priority.READING_NOW else Priority.DEFAULT
	}

	fun <T> orderForScheduling(
		items: Collection<T>,
		mangaProvider: (T) -> Manga,
	): List<T> {
		if (items.size <= 1) {
			return items.toList()
		}
		val ordered = items.mapIndexed { index, item ->
			val manga = mangaProvider(item)
			ScheduledItem(
				item = item,
				manga = manga,
				index = index,
				priority = priorityFor(manga),
			)
		}.sortedWith(
			compareBy<ScheduledItem<T>> { it.priority.rank }.thenBy { it.index },
		)
		return distributeBySource(ordered)
	}

	suspend fun enqueue(task: QueueEntry) = mutex.withLock {
		queue.add(task)
	}

	suspend fun enqueueAll(tasks: Collection<QueueEntry>) = mutex.withLock {
		queue.addAll(tasks)
	}

	suspend fun dequeue(): QueueEntry? = mutex.withLock {
		val index = queue.nextIndexOrNull() ?: return@withLock null
		queue.removeAt(index)
	}

	suspend fun peek(): QueueEntry? = mutex.withLock {
		val index = queue.nextIndexOrNull() ?: return@withLock null
		queue[index]
	}

	suspend fun remove(mangaId: Long) = mutex.withLock {
		queue.removeAll { it.mangaId == mangaId }
	}

	suspend fun isEmpty(): Boolean = mutex.withLock {
		queue.isEmpty()
	}

	suspend fun size(): Int = mutex.withLock {
		queue.size
	}

	suspend fun clear() = mutex.withLock {
		queue.clear()
	}

	suspend fun getPendingIds(): List<Long> = mutex.withLock {
		queue.sortedWith(queueComparator).map { it.mangaId }
	}

	private fun List<QueueEntry>.nextIndexOrNull(): Int? {
		if (isEmpty()) return null
		var bestIndex = 0
		for (i in 1 until size) {
			if (queueComparator.compare(this[i], this[bestIndex]) < 0) {
				bestIndex = i
			}
		}
		return bestIndex
	}

	private fun <T> distributeBySource(items: List<ScheduledItem<T>>): List<T> {
		val lanes = LinkedHashMap<String, ArrayDeque<ScheduledItem<T>>>()
		for (item in items) {
			lanes.getOrPut(item.manga.source.name) { ArrayDeque() }.addLast(item)
		}
		val result = ArrayList<T>(items.size)
		while (lanes.isNotEmpty()) {
			val iterator = lanes.entries.iterator()
			while (iterator.hasNext()) {
				val lane = iterator.next().value
				result.add(lane.removeFirst().item)
				if (lane.isEmpty()) {
					iterator.remove()
				}
			}
		}
		return result
	}

	private val Priority.rank: Int
		get() = when (this) {
			Priority.READING_NOW -> 0
			Priority.FAVORITE_RECENT -> 1
			Priority.FAVORITE_OTHER -> 2
			Priority.DEFAULT -> 3
		}

	private data class ScheduledItem<T>(
		val item: T,
		val manga: Manga,
		val index: Int,
		val priority: Priority,
	)

	data class QueueEntry(
		val mangaId: Long,
		val manga: Manga? = null,
		val priority: Priority = Priority.DEFAULT,
		val addedAtMs: Long = System.currentTimeMillis(),
		val workId: UUID? = null,
	)

	enum class Priority {
		READING_NOW,
		FAVORITE_RECENT,
		FAVORITE_OTHER,
		DEFAULT,
	}
}
