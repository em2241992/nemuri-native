package org.koitharu.kotatsu.explore.ui

import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup
import org.koitharu.kotatsu.explore.ui.adapter.ExploreAdapter
import org.koitharu.kotatsu.explore.ui.model.MangaSourceItem

class ExploreGridSpanSizeLookup(
	private val adapter: ExploreAdapter,
	private val layoutManager: GridLayoutManager,
) : SpanSizeLookup() {

	init {
		isSpanIndexCacheEnabled = false
		isSpanGroupIndexCacheEnabled = false
	}

	override fun getSpanSize(position: Int): Int {
		val item = adapter.items.getOrNull(position) ?: return layoutManager.spanCount
		return if (item is MangaSourceItem && item.isGrid) 1 else layoutManager.spanCount
	}
}
