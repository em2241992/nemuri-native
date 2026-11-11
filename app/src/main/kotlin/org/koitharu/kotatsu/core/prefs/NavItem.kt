package org.koitharu.kotatsu.core.prefs

import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.Keep
import androidx.annotation.StringRes
import org.koitharu.kotatsu.R

@Keep
enum class NavItem(
	@IdRes val id: Int,
	@StringRes val title: Int,
	@DrawableRes val icon: Int,
) {

	FAVORITES(R.id.nav_favorites, R.string.library, R.drawable.ic_favourites_selector),
	HISTORY(R.id.nav_history, R.string.history, R.drawable.ic_history_selector),
	FEED(R.id.nav_feed, R.string.feed, R.drawable.ic_feed_selector),
	EXPLORE(R.id.nav_explore, R.string.explore, R.drawable.ic_explore_selector),
	ENHANCE(R.id.nav_enhance, R.string.enhance, R.drawable.ic_enhance_selector),
	LOCAL(R.id.nav_local, R.string.on_device, R.drawable.ic_storage_selector),
	SUGGESTIONS(R.id.nav_suggestions, R.string.suggestions, R.drawable.ic_suggestion_selector),
	UPDATED(R.id.nav_updated, R.string.updated, R.drawable.ic_updated_selector),
	BOOKMARKS(R.id.nav_bookmarks, R.string.bookmarks, R.drawable.ic_bookmark_selector),
	;

	fun isAvailable(settings: AppSettings): Boolean = when (this) {
		SUGGESTIONS -> settings.isSuggestionsEnabled
		UPDATED -> settings.isTrackerEnabled
		else -> true
	}
}
