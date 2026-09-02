package org.koharu.miyo.core.model

import org.koitharu.kotatsu.parsers.model.MangaSource

data class MangaSourceInfo(
	val mangaSource: MangaSource,
	val isEnabled: Boolean,
	val isPinned: Boolean,
	val isHidden: Boolean = false,
	val priority: Int = 0,
) : MangaSource by mangaSource
