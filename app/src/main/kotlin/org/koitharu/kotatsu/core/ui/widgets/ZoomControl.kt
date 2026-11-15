package org.koitharu.kotatsu.core.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.hapticFeedback
import org.koitharu.kotatsu.databinding.ViewZoomBinding

class ZoomControl @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : LinearLayout(context, attrs), View.OnClickListener {

	private val binding = ViewZoomBinding.inflate(LayoutInflater.from(context), this)

	var listener: ZoomControlListener? = null

	init {
		orientation = VERTICAL
		gravity = Gravity.CENTER_HORIZONTAL
		setBackgroundResource(R.drawable.bg_zoom_slate_capsule)
		clipToOutline = true
		elevation = 6f * resources.displayMetrics.density
		binding.buttonZoomIn.setOnClickListener(this)
		binding.buttonZoomOut.setOnClickListener(this)
	}

	override fun onClick(v: View) {
		v.hapticFeedback(HapticEffect.LIGHT_CLICK)
		when (v.id) {
			R.id.button_zoom_in -> listener?.onZoomIn()
			R.id.button_zoom_out -> listener?.onZoomOut()
		}
	}

	interface ZoomControlListener {

		fun onZoomIn()

		fun onZoomOut()
	}
}
