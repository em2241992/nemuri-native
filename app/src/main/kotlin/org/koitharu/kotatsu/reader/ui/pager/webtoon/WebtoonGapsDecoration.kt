package org.koitharu.kotatsu.reader.ui.pager.webtoon

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R

class WebtoonGapsDecoration(
	private val smartSeamAutoStitch: Boolean = true,
) : RecyclerView.ItemDecoration() {

	private var gapSize = -1

	override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
		super.getItemOffsets(outRect, view, parent, state)
		val position = parent.getChildAdapterPosition(view)
		if (position <= 0) {
			outRect.setEmpty()
			return
		}
		if (smartSeamAutoStitch && isSlicedContinuousStripPair(parent, position, view)) {
			outRect.top = 0
			return
		}
		outRect.top = getGap(parent.context)
	}

	private fun isSlicedContinuousStripPair(
		parent: RecyclerView,
		position: Int,
		currentView: View,
	): Boolean {
		val currentSsiv = currentView.findViewById<WebtoonImageView>(R.id.ssiv) ?: return false
		val prevHolder = parent.findViewHolderForAdapterPosition(position - 1)
		val prevSsiv = prevHolder?.itemView?.findViewById<WebtoonImageView>(R.id.ssiv)
		if (currentSsiv.sWidth > 0 && currentSsiv.sHeight > 0) {
			// Tall webtoon strip or sliced tail strip matching previous strip width
			if (currentSsiv.sHeight >= (currentSsiv.sWidth * 1.45f)) {
				return true
			}
			if (prevSsiv != null && prevSsiv.sWidth == currentSsiv.sWidth &&
				(prevSsiv.sHeight >= prevSsiv.sWidth * 1.45f || currentSsiv.sHeight < currentSsiv.sWidth)
			) {
				return true
			}
		}
		return false
	}

	private fun getGap(context: Context): Int {
		return if (gapSize == -1) {
			context.resources.getDimensionPixelOffset(R.dimen.webtoon_pages_gap).also {
				gapSize = it
			}
		} else {
			gapSize
		}
	}
}
