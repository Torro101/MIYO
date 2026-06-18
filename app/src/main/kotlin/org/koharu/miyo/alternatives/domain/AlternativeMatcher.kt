package org.koharu.miyo.alternatives.domain

import org.koharu.miyo.core.model.chaptersCount
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.levenshteinDistance
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val BRACKETED_QUALIFIER_REGEX = Regex("\\[[^]]*(scan|official|webtoon|colored|colour|color|raw)[^]]*]")
private val PAREN_QUALIFIER_REGEX = Regex("\\([^)]*(scan|official|webtoon|colored|colour|color|raw)[^)]*\\)")
private val APOSTROPHE_REGEX = Regex("['’`´]")
private val NON_WORD_REGEX = Regex("[^\\p{L}\\p{N}]+")
private val GENERIC_WORD_REGEX = Regex("\\b(the|a|an|manga|manhwa|manhua|comic|comics|official|webtoon)\\b")
private val WHITESPACE_REGEX = Regex("\\s+")

/**
 * Shared identity scoring for replacement candidates.
 *
 * Alternatives can move history, favourites and tracker state, so the matcher is
 * intentionally conservative: chapter availability helps ordering, but it never
 * proves that two manga are the same work by itself.
 */
class AlternativeMatcher @Inject constructor() {

	fun score(seed: Manga, candidate: Manga): AlternativeScore {
		if (seed.id == candidate.id) {
			return AlternativeScore(
				value = 0,
				titleScore = 0,
				metadataScore = 0,
				chapterScore = 0,
				reasons = listOf("same library entry"),
			)
		}
		val titleScore = bestTitleScore(seed.titleCandidates(), candidate.titleCandidates())
		val metadata = metadataScore(seed, candidate)
		val seedChapters = seed.chaptersCount()
		val candidateChapters = candidate.chaptersCount()
		val chapters = chapterScore(seedChapters, candidateChapters)
		val hasMetadataSignal = hasComparableMetadata(seed, candidate)
		val hasChapterSignal = seedChapters > 0 && candidateChapters > 0
		val supportingScores = buildList {
			if (hasMetadataSignal) {
				add(metadata)
			}
			if (hasChapterSignal) {
				add(chapters)
			}
		}
		val value = if (supportingScores.isNotEmpty()) {
			(titleScore * 0.82f + supportingScores.averageOrZero() * 0.18f).roundToInt()
		} else {
			// Sparse parser results are common for broken sources. Preserve exact
			// title confidence instead of forcing metadata that the source cannot supply.
			titleScore
		}.coerceIn(0, 100)
		return AlternativeScore(
			value = value,
			titleScore = titleScore,
			metadataScore = metadata,
			chapterScore = chapters,
			reasons = buildReasons(titleScore, metadata, chapters, hasChapterSignal),
		)
	}

	private fun Manga.titleCandidates(): Set<String> = buildSet {
		add(title)
		altTitle?.let(::add)
		addAll(altTitles)
	}

	private fun bestTitleScore(a: Set<String>, b: Set<String>): Int {
		var best = 0
		val leftKeys = a.map(::TitleKey)
		val rightKeys = b.map(::TitleKey)
		for (left in leftKeys) {
			for (right in rightKeys) {
				best = max(best, titleScore(left, right))
			}
		}
		return best
	}

	private fun titleScore(a: TitleKey, b: TitleKey): Int {
		if (a.value.isEmpty() || b.value.isEmpty()) {
			return 0
		}
		if (a.value == b.value) {
			return 100
		}
		val edit = normalizedEditScore(a.value, b.value)
		val token = tokenScore(a.tokens, b.tokens)
		val containment = when {
			a.tokens.size <= 1 || b.tokens.size <= 1 -> 0
			a.tokens.containsAll(b.tokens) || b.tokens.containsAll(a.tokens) -> 82
			else -> 0
		}
		val raw = max(max(edit, token), containment)
		return if (a.isShort || b.isShort) {
			// Short titles are collision-prone: "Monster" vs "Monster Musume"
			// should not become an auto-migration just because one token matches.
			min(raw, if (a.value.firstOrNull() == b.value.firstOrNull()) edit else 70)
		} else {
			raw
		}
	}

	private fun hasComparableMetadata(seed: Manga, candidate: Manga): Boolean {
		return (seed.authors.isNotEmpty() && candidate.authors.isNotEmpty()) ||
			(seed.contentRating != null && candidate.contentRating != null) ||
			(seed.state != null && candidate.state != null) ||
			(seed.tags.isNotEmpty() && candidate.tags.isNotEmpty())
	}

	private fun metadataScore(seed: Manga, candidate: Manga): Int {
		var score = 0
		var weight = 0
		if (seed.authors.isNotEmpty() && candidate.authors.isNotEmpty()) {
			weight += 35
			if (seed.authors.any { author -> candidate.authors.any { it.equals(author, ignoreCase = true) } }) {
				score += 35
			}
		}
		if (seed.contentRating != null && candidate.contentRating != null) {
			weight += 20
			if (seed.contentRating == candidate.contentRating) {
				score += 20
			}
		}
		if (seed.state != null && candidate.state != null) {
			weight += 10
			if (seed.state == candidate.state) {
				score += 10
			}
		}
		if (seed.tags.isNotEmpty() && candidate.tags.isNotEmpty()) {
			weight += 35
			val seedTags = seed.tags.mapTo(HashSet()) { it.title.lowercase(Locale.ROOT) }
			val candidateTags = candidate.tags.mapTo(HashSet()) { it.title.lowercase(Locale.ROOT) }
			val overlap = seedTags.count { it in candidateTags }
			if (overlap > 0) {
				score += (35f * overlap / max(seedTags.size, candidateTags.size)).toInt()
			}
		}
		return if (weight == 0) 0 else (score * 100 / weight).coerceIn(0, 100)
	}

	private fun List<Int>.averageOrZero(): Int {
		return if (isEmpty()) 0 else (sum().toFloat() / size).roundToInt()
	}

	private fun chapterScore(seedCount: Int, candidateCount: Int): Int {
		if (seedCount <= 0 || candidateCount <= 0) {
			return 0
		}
		val distance = abs(seedCount - candidateCount).toFloat() / max(seedCount, candidateCount)
		return ((1f - distance).coerceIn(0f, 1f) * 100).toInt()
	}

	private fun buildReasons(
		titleScore: Int,
		metadataScore: Int,
		chapterScore: Int,
		hasChapterSignal: Boolean,
	): List<String> = buildList {
		when {
			titleScore >= 95 -> add("exact title match")
			titleScore >= 82 -> add("strong title match")
			titleScore >= 68 -> add("possible title match")
			else -> add("weak title match")
		}
		if (metadataScore >= 70) {
			add("metadata agrees")
		}
		if (chapterScore >= 75) {
			add("chapter count is close")
		} else if (hasChapterSignal) {
			add("chapter count differs")
		}
	}

	private fun normalizedEditScore(left: String, right: String): Int {
		val maxLength = max(left.length, right.length)
		if (maxLength == 0) {
			return 0
		}
		val distance = left.levenshteinDistance(right)
		return ((1f - distance.toFloat() / maxLength) * 100).toInt().coerceIn(0, 100)
	}

	private fun tokenScore(left: Set<String>, right: Set<String>): Int {
		if (left.isEmpty() || right.isEmpty()) {
			return 0
		}
		val intersection = left.count { it in right }
		val union = left.size + right.size - intersection
		return (intersection * 100 / union).coerceIn(0, 100)
	}

	private class TitleKey(raw: String) {
		val value: String = raw.canonicalTitle().ifEmpty { raw.fallbackCanonicalTitle() }
		val tokens: Set<String> = value.split(' ').filterTo(LinkedHashSet()) { it.isNotEmpty() }
		val isShort: Boolean = value.length <= 4 || tokens.size <= 1
	}

	private fun String.canonicalTitle(): String {
		val normalized = Normalizer.normalize(this, Normalizer.Form.NFKC)
		return normalized
			.lowercase(Locale.ROOT)
			.replace(BRACKETED_QUALIFIER_REGEX, " ")
			.replace(PAREN_QUALIFIER_REGEX, " ")
			.replace('&', ' ')
			.replace(APOSTROPHE_REGEX, "")
			.replace(NON_WORD_REGEX, " ")
			.replace(GENERIC_WORD_REGEX, " ")
			.replace(WHITESPACE_REGEX, " ")
			.trim()
	}

	private fun String.fallbackCanonicalTitle(): String {
		return Normalizer.normalize(this, Normalizer.Form.NFKC)
			.lowercase(Locale.ROOT)
			.replace('&', ' ')
			.replace(APOSTROPHE_REGEX, "")
			.replace(NON_WORD_REGEX, " ")
			.replace(WHITESPACE_REGEX, " ")
			.trim()
	}
}

data class AlternativeScore(
	val value: Int,
	val titleScore: Int,
	val metadataScore: Int,
	val chapterScore: Int,
	val reasons: List<String>,
) {

	val isDisplayable: Boolean
		get() = titleScore >= DISPLAY_TITLE_THRESHOLD || value >= DISPLAY_SCORE_THRESHOLD

	val isAutoMigrationSafe: Boolean
		get() = titleScore >= AUTO_TITLE_THRESHOLD &&
			value >= AUTO_SCORE_THRESHOLD &&
			(metadataScore >= AUTO_SUPPORT_THRESHOLD || chapterScore >= AUTO_SUPPORT_THRESHOLD)

	companion object {
		private const val DISPLAY_TITLE_THRESHOLD = 58
		private const val DISPLAY_SCORE_THRESHOLD = 55
		private const val AUTO_TITLE_THRESHOLD = 86
		private const val AUTO_SCORE_THRESHOLD = 78
		private const val AUTO_SUPPORT_THRESHOLD = 70
	}
}

data class AlternativeCandidate(
	val manga: Manga,
	val score: AlternativeScore,
)
