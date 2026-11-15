package org.koitharu.kotatsu.core.prefs

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R

enum class DedupeWinnerRule(
	@StringRes val titleResId: Int,
) {

	MOST_CHAPTERS(R.string.enhance_winner_most_chapters),
	FURTHEST_READ(R.string.enhance_winner_furthest_read),
	HEALTHY_SOURCE(R.string.enhance_winner_healthy_source),
	HIGHEST_RATING(R.string.enhance_winner_highest_rating),
}
