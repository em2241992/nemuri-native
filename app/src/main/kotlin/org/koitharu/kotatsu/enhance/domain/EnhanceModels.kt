package org.koitharu.kotatsu.enhance.domain

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource

enum class EnhanceTab(
	@StringRes val labelRes: Int,
) {
	DUPLICATES(R.string.enhance_tab_duplicates),
	REPAIR(R.string.enhance_tab_repair),
	UPSCALER(R.string.enhance_tab_upscaler),
}

@Immutable
data class LibraryEntrySnapshot(
	val manga: Manga,
	val sourceTitle: String,
	val sourceKey: String,
	val chaptersCount: Int,
	val readProgress: Float,
	val categoryIds: Set<Long>,
	val isHealthySource: Boolean,
	val rating: Float,
	val priorityIndex: Int,
)

@Immutable
data class DuplicatePairItem(
	val pairKey: String,
	val left: LibraryEntrySnapshot,
	val right: LibraryEntrySnapshot,
	val similarity: Float,
	val isRightPrimary: Boolean,
) {
	val primary: LibraryEntrySnapshot
		get() = if (isRightPrimary) right else left

	val secondary: LibraryEntrySnapshot
		get() = if (isRightPrimary) left else right
}

enum class RepairIssueKind {
	UNAVAILABLE_SOURCE,
	UNREACHABLE_SOURCE,
	ZERO_CHAPTERS,
	MISSING_COVER,
}

@Immutable
data class RepairItem(
	val manga: Manga,
	val sourceTitle: String,
	val issueKind: RepairIssueKind,
	val reasonText: String,
	val isRefreshing: Boolean = false,
)

@Immutable
data class PrioritySourceCandidate(
	val key: String,
	val source: MangaSource,
	val displayName: String,
	val langBadge: String,
)

@Immutable
data class UpscaleSampleItem(
	val mangaId: Long,
	val title: String,
	val coverUrl: String,
	val source: MangaSource,
)
