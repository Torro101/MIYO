package org.koharu.miyo.alternatives.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koharu.miyo.core.parser.MangaRepository
import org.koharu.miyo.core.util.ext.toLocale
import org.koharu.miyo.explore.data.MangaSourcesRepository
import org.koharu.miyo.search.domain.SearchKind
import org.koharu.miyo.search.domain.SearchV2Helper
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.util.Locale
import javax.inject.Inject

private const val SEARCH_PARALLELISM = 4
private const val DETAILS_PARALLELISM = 4

class AlternativesUseCase @Inject constructor(
	private val sourcesRepository: MangaSourcesRepository,
	private val searchHelperFactory: SearchV2Helper.Factory,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val matcher: AlternativeMatcher,
) {

	suspend operator fun invoke(manga: Manga, throughDisabledSources: Boolean): Flow<AlternativeCandidate> {
		val sources = getSources(manga.source, throughDisabledSources)
		if (sources.isEmpty()) {
			return emptyFlow()
		}
		val searchSemaphore = Semaphore(SEARCH_PARALLELISM)
		val detailsSemaphore = Semaphore(DETAILS_PARALLELISM)
		return channelFlow {
			for (source in sources) {
				launch {
					val searchHelper = searchHelperFactory.create(source)
					val list = runCatchingCancellable {
						searchSemaphore.withPermit {
							searchHelper(manga.title, SearchKind.TITLE)?.manga
						}
					}.getOrNull()
					val candidates = list.orEmpty()
						.asSequence()
						.filter { it.id != manga.id }
						.map { AlternativeCandidate(it, matcher.score(manga, it)) }
						.sortedByDescending { it.score.value }
						.toList()
					for (candidate in candidates) {
						launch {
							val details = runCatchingCancellable {
								detailsSemaphore.withPermit {
									mangaRepositoryFactory.create(candidate.manga.source).getDetails(candidate.manga)
								}
							}.getOrDefault(candidate.manga)
							val score = matcher.score(manga, details)
							send(AlternativeCandidate(details, score))
						}
					}
				}
			}
		}
	}

	private suspend fun getSources(ref: MangaSource, disabled: Boolean): List<MangaSource> = if (disabled) {
		sourcesRepository.getDisabledSources()
	} else {
		sourcesRepository.getEnabledSources()
	}.sortedByDescending { it.priority(ref) }

	private fun MangaSource.priority(ref: MangaSource): Int {
		var res = 0
		if (locale == ref.locale) {
			res += 4
		} else if (locale.toLocale() == Locale.getDefault()) {
			res += 2
		}
		if (contentType == ref.contentType) {
			res++
		}
		return res
	}
}
