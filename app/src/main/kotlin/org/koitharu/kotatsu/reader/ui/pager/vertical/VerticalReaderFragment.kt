package org.koitharu.kotatsu.reader.ui.pager.vertical

import androidx.viewpager2.widget.ViewPager2
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.core.util.ext.recyclerView
import org.koitharu.kotatsu.reader.ui.pager.BasePagerReaderFragment

@AndroidEntryPoint
class VerticalReaderFragment : BasePagerReaderFragment() {

	override fun onInitPager(pager: ViewPager2) {
		super.onInitPager(pager)
		pager.orientation = ViewPager2.ORIENTATION_VERTICAL
		pager.recyclerView?.apply {
			itemAnimator = null
			setPadding(0, 0, 0, 0)
			clipToPadding = false
		}
	}

	override fun onCreateAdvancedTransformer(): ViewPager2.PageTransformer = VerticalPageAnimTransformer()
}
