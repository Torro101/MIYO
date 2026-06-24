package org.koharu.miyo.core.parser.tachiyomi

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import java.util.Locale

/**
 * Bidirectional mapper between Tachiyomi source models and Kotatsu parser models.
 *
 * The Tachiyomi extension system uses its own model classes (SManga, SChapter, Page),
 * while MIYO's core uses Kotatsu parser models (Manga, MangaChapter, MangaPage).
 * This mapper provides lossless translation in both directions.
 */
object TachiyomiModelMapper {

	// ============================================================================
	// Tachiyomi → Kotatsu
	// ============================================================================

	/**
	 * Maps a Tachiyomi [SManga] to a Kotatsu [Manga].
	 *
	 * @param sManga The Tachiyomi manga model
	 * @param source The Kotatsu MangaSource to associate with the manga
	 * @param id A stable ID derived from the source and URL (uses hash of url + source name)
	 */
	fun toManga(sManga: SManga, source: MangaSource, id: Long = generateId(sManga.url, source.name)): Manga {
		return Manga(
			id = id,
			title = sManga.title ?: "Unknown",
			altTitles = emptySet(),
			url = sManga.url,
			publicUrl = sManga.url,
			rating = -1f,
			contentRating = mapContentRating(sManga),
			coverUrl = sManga.thumbnail_url ?: "",
			tags = parseGenres(sManga.genre, source),
			state = mapMangaState(sManga.status),
			authors = buildSet {
				sManga.author?.let { add(it.trim()) }
				sManga.artist?.let { if (it.trim() != sManga.author?.trim()) add(it.trim()) }
			},
			largeCoverUrl = null,
			description = sManga.description,
			chapters = null,
			source = source,
		)
	}

	/**
	 * Maps a Tachiyomi [SChapter] to a Kotatsu [MangaChapter].
	 *
	 * @param sChapter The Tachiyomi chapter model
	 * @param source The Kotatsu MangaSource to associate
	 * @param mangaId The ID of the parent manga (for chapter ID generation)
	 */
	fun toMangaChapter(sChapter: SChapter, source: MangaSource, mangaId: Long): MangaChapter {
		return MangaChapter(
			id = generateId(sChapter.url, source.name),
			title = sChapter.name,
			number = sChapter.chapter_number,
			volume = 0,
			url = sChapter.url,
			scanlator = sChapter.scanlator,
			uploadDate = sChapter.date_upload,
			branch = null,
			source = source,
		)
	}

	/**
	 * Maps a list of Tachiyomi [SChapter] to Kotatsu [MangaChapter] list.
	 */
	fun toMangaChapters(sChapters: List<SChapter>, source: MangaSource, mangaId: Long): List<MangaChapter> {
		return sChapters.map { toMangaChapter(it, source, mangaId) }
	}

	/**
	 * Maps a Tachiyomi [Page] to a Kotatsu [MangaPage].
	 *
	 * @param page The Tachiyomi page model
	 * @param source The Kotatsu MangaSource to associate
	 */
	fun toMangaPage(page: Page, source: MangaSource): MangaPage {
		return MangaPage(
			id = page.index.toLong(),
			url = page.imageUrl ?: page.url,
			preview = null,
			source = source,
		)
	}

	/**
	 * Maps a list of Tachiyomi [Page] to Kotatsu [MangaPage] list.
	 */
	fun toMangaPages(pages: List<Page>, source: MangaSource): List<MangaPage> {
		return pages.map { toMangaPage(it, source) }
	}

	/**
	 * Maps a Tachiyomi [MangasPage] to a Kotatsu manga list + hasNextPage flag.
	 */
	fun toMangaList(mangasPage: MangasPage, source: MangaSource): Pair<List<Manga>, Boolean> {
		val mangaList = mangasPage.mangas.map { toManga(it, source) }
		return Pair(mangaList, mangasPage.hasNextPage)
	}

	// ============================================================================
	// Kotatsu → Tachiyomi
	// ============================================================================

	/**
	 * Maps a Kotatsu [Manga] to a Tachiyomi [SManga].
	 * Used when the extension needs to fetch details or chapters for a manga
	 * that was created from a deep link or search result.
	 */
	fun toSManga(manga: Manga): SManga {
		return SManga.create().also {
			it.url = manga.url
			it.title = manga.title
			it.author = manga.authors.firstOrNull()
			it.artist = manga.authors.elementAtOrNull(1)
			it.description = manga.description
			it.genre = manga.tags.joinToString(", ") { tag -> tag.title }
			it.status = mapToTachiyomiStatus(manga.state)
			it.thumbnail_url = manga.coverUrl
			it.initialized = manga.chapters != null
		}
	}

	/**
	 * Maps a Kotatsu [MangaChapter] to a Tachiyomi [SChapter].
	 */
	fun toSChapter(chapter: MangaChapter): SChapter {
		return SChapter.create().also {
			it.url = chapter.url
			it.name = chapter.title ?: "Ch. ${chapter.number}"
			it.chapter_number = chapter.number
			it.date_upload = chapter.uploadDate
			it.scanlator = chapter.scanlator
		}
	}

	// ============================================================================
	// Internal helpers
	// ============================================================================

	private fun mapMangaState(status: Int): MangaState? = when (status) {
		SManga.ONGOING, SManga.PUBLISHING_FINISHED -> MangaState.ONGOING
		SManga.COMPLETED -> MangaState.FINISHED
		SManga.LICENSED -> MangaState.ABANDONED
		SManga.ON_HIATUS -> MangaState.PAUSED
		SManga.CANCELLED -> MangaState.ABANDONED
		else -> null
	}

	private fun mapToTachiyomiStatus(state: MangaState?): Int = when (state) {
		MangaState.ONGOING -> SManga.ONGOING
		MangaState.FINISHED -> SManga.COMPLETED
		MangaState.ABANDONED -> SManga.CANCELLED
		MangaState.PAUSED -> SManga.ON_HIATUS
		MangaState.UPCOMING -> SManga.ONGOING
		MangaState.RESTRICTED -> SManga.ONGOING
		null -> SManga.UNKNOWN
	}

	private fun mapContentRating(sManga: SManga): ContentRating? {
		// SManga doesn't have a direct content rating field.
		// Heuristic: if genre contains common NSFW keywords, mark as ADULT.
		val genre = sManga.genre?.lowercase(Locale.ROOT) ?: return null
		val nsfwKeywords = listOf("hentai", "adult", "mature", "18+", "nsfw", "ecchi")
		return if (nsfwKeywords.any { genre.contains(it) }) ContentRating.ADULT else null
	}

	private fun parseGenres(genre: String?, source: MangaSource): Set<MangaTag> {
		if (genre.isNullOrBlank()) return emptySet()
		return genre.split(",").mapNotNull { g ->
			val trimmed = g.trim()
			if (trimmed.isNotBlank()) MangaTag(title = trimmed, key = trimmed.lowercase(Locale.ROOT), source = source)
			else null
		}.toSet()
	}

	/**
	 * Generates a stable Long ID from a URL and source name.
	 * Uses a simple hash to ensure consistency across app restarts.
	 */
	fun generateId(url: String, sourceName: String): Long {
		val combined = "$sourceName:$url"
		var hash = 0L
		for (c in combined) {
			hash = hash * 31L + c.code
		}
		return hash and 0x7FFFFFFFFFFFFFFFL // Keep positive
	}
}