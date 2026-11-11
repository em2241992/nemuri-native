package org.koitharu.kotatsu.tracker.ui.feed.model

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.model.DateTimeAgo
import org.koitharu.kotatsu.parsers.model.Manga
import java.time.Instant

enum class NemuriFeedMode(
	val id: String,
	@StringRes val labelRes: Int,
) {
	UPDATES("updates", R.string.feed_mode_updates),
	HUB("hub", R.string.feed_mode_hub),
	GALLERY("gallery", R.string.feed_mode_gallery),
	FOR_YOU("for_you", R.string.feed_mode_for_you);

	companion object {
		fun fromId(id: String?): NemuriFeedMode = entries.firstOrNull { it.id == id } ?: UPDATES
	}
}

enum class NemuriFeedFilter(
	val id: String,
	@StringRes val labelRes: Int,
) {
	ALL("all", R.string.feed_filter_all),
	MY_FOLLOWS("follows", R.string.feed_filter_my_follows),
	UNREAD_ONLY("unread", R.string.feed_filter_unread_only),
	DOWNLOADED("downloaded", R.string.feed_filter_downloaded),
}

enum class NemuriHubSection(
	@StringRes val titleRes: Int,
) {
	FEED_UPDATES(R.string.feed_hub_updates_title),
	POPULAR_NEW(R.string.feed_hub_popular_new_title),
	LATEST_UPLOADS(R.string.feed_hub_latest_uploads_title),
}

data class FeedChapterStreamRow(
	val key: String,
	val logId: Long,
	val manga: Manga,
	val chapterId: Long?,
	val chapterTitle: String,
	val createdAt: Instant,
	val dateBucket: DateTimeAgo?,
	val isUnread: Boolean,
	val inLibrary: Boolean,
	val isDownloaded: Boolean,
)

data class FeedDateGroup(
	val key: String,
	val dateBucket: DateTimeAgo?,
	val rows: List<FeedChapterStreamRow>,
)

data class FeedDiscoveryCardItem(
	val key: String,
	val manga: Manga,
	val subtitleOverride: String? = null,
	val chapterId: Long? = null,
	val chapterPill: String? = null,
	val matchedTags: List<String> = emptyList(),
	val inLibrary: Boolean = false,
	val isUnread: Boolean = true,
	val isDownloaded: Boolean = false,
)

data class UpdatesStreamUiState(
	val groups: List<FeedDateGroup> = emptyList(),
	val totalUnfilteredCount: Int = 0,
	val isInitialLoading: Boolean = true,
)

data class HubUiState(
	val feedUpdates: List<FeedDiscoveryCardItem> = emptyList(),
	val popularNewTitles: List<FeedDiscoveryCardItem> = emptyList(),
	val latestUploads: List<FeedDiscoveryCardItem> = emptyList(),
	val isDiscoveryLoading: Boolean = false,
	val hasEnabledSources: Boolean = true,
)

data class GalleryUiState(
	val activeSection: NemuriHubSection? = null,
	val items: List<FeedDiscoveryCardItem> = emptyList(),
	val isLoading: Boolean = false,
	val totalUnfilteredCount: Int = 0,
)

data class ForYouUiState(
	val affinityTags: List<String> = emptyList(),
	val selectedTag: String? = null,
	val items: List<FeedDiscoveryCardItem> = emptyList(),
	val isLoading: Boolean = false,
	val isLibraryAndHistoryEmpty: Boolean = false,
)
