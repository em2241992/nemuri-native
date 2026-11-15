package org.koitharu.kotatsu.core.prefs

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R

enum class PageCounterPlacement(
	@StringRes val titleResId: Int,
) {
	BOTTOM_CENTER(R.string.page_counter_bottom_center),
	TOP_BAR(R.string.page_counter_top_bar),
	BOTH(R.string.page_counter_both),
	HIDDEN(R.string.page_counter_hidden),
	;

	val showsBottomCounter: Boolean
		get() = this == BOTTOM_CENTER || this == BOTH

	val showsTopBarCounter: Boolean
		get() = this == TOP_BAR || this == BOTH
}
