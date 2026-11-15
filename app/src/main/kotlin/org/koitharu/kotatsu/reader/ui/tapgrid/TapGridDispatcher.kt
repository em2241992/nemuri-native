package org.koitharu.kotatsu.reader.ui.tapgrid

import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.hapticFeedback
import org.koitharu.kotatsu.reader.domain.TapGridArea
import kotlin.math.hypot
import kotlin.math.roundToInt

class TapGridDispatcher(
	private val rootView: View,
	private val listener: OnGridTouchListener,
) : GestureDetector.SimpleOnGestureListener() {

	private val detector = GestureDetector(rootView.context, this)
	private val handler = Handler(Looper.getMainLooper())
	private val touchSlopPx = minOf(
		ViewConfiguration.get(rootView.context).scaledTouchSlop.toFloat(),
		10f * rootView.resources.displayMetrics.density,
	).coerceAtLeast(10f)

	private var isDispatching = false
	private var isLongPressPending = false
	private var longPressConsumed = false
	private var downRawX = 0f
	private var downRawY = 0f

	private val longPressRunnable = Runnable {
		if (!isLongPressPending || !isDispatching || !listener.isReaderScrollIdle()) {
			isLongPressPending = false
			return@Runnable
		}
		isLongPressPending = false
		val area = getArea(downRawX, downRawY) ?: TapGridArea.CENTER
		if (listener.onGridLongTouch(area)) {
			longPressConsumed = true
			isDispatching = false
			rootView.hapticFeedback(HapticEffect.LONG_PRESS)
		}
	}

	init {
		detector.setIsLongpressEnabled(false)
		detector.setOnDoubleTapListener(this)
	}

	fun dispatchTouchEvent(event: MotionEvent) {
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				cancelLongPress()
				longPressConsumed = false
				downRawX = event.rawX
				downRawY = event.rawY
				isDispatching = listener.onProcessTouch(event.rawX.toInt(), event.rawY.toInt())
				if (isDispatching && event.pointerCount == 1 && listener.isReaderScrollIdle()) {
					isLongPressPending = true
					handler.postDelayed(longPressRunnable, LONG_PRESS_DELAY_MS)
				}
			}

			MotionEvent.ACTION_POINTER_DOWN -> {
				cancelLongPress()
			}

			MotionEvent.ACTION_MOVE -> {
				if (isLongPressPending) {
					val moved = hypot(event.rawX - downRawX, event.rawY - downRawY)
					if (event.pointerCount > 1 || moved > touchSlopPx || !listener.isReaderScrollIdle()) {
						cancelLongPress()
					}
				}
			}

			MotionEvent.ACTION_UP,
			MotionEvent.ACTION_CANCEL -> {
				cancelLongPress()
				if (longPressConsumed) {
					isDispatching = false
				}
			}
		}
		if (event.pointerCount > 1) {
			cancelLongPress()
		}
		detector.onTouchEvent(event)
	}

	private fun cancelLongPress() {
		if (isLongPressPending) {
			isLongPressPending = false
			handler.removeCallbacks(longPressRunnable)
		}
	}

	override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
		if (!isDispatching || longPressConsumed) {
			return true
		}
		val area = getArea(event.rawX, event.rawY) ?: return false
		val location = IntArray(2)
		rootView.getLocationOnScreen(location)
		val horizontalFraction = ((event.rawX - location[0]) / rootView.width.coerceAtLeast(1)).coerceIn(0f, 1f)
		return listener.onGridTouch(area, horizontalFraction)
	}

	override fun onDoubleTapEvent(e: MotionEvent): Boolean {
		isDispatching = false // ignore long press after double tap
		cancelLongPress()
		return super.onDoubleTapEvent(e)
	}

	private fun getArea(x: Float, y: Float): TapGridArea? {
		val width = rootView.width
		val height = rootView.height
		if (height <= 0 || width <= 0) {
			return null
		}
		val xIndex = (x * 2f / width).roundToInt().coerceIn(0, 2)
		val yIndex = (y * 2f / height).roundToInt().coerceIn(0, 2)
		return when (xIndex) {
			0 -> when (yIndex) { // LEFT
				0 -> TapGridArea.TOP_LEFT
				1 -> TapGridArea.CENTER_LEFT
				2 -> TapGridArea.BOTTOM_LEFT
				else -> null
			}

			1 -> when (yIndex) { // CENTER
				0 -> TapGridArea.TOP_CENTER
				1 -> TapGridArea.CENTER
				2 -> TapGridArea.BOTTOM_CENTER
				else -> null
			}

			2 -> when (yIndex) { // RIGHT
				0 -> TapGridArea.TOP_RIGHT
				1 -> TapGridArea.CENTER_RIGHT
				2 -> TapGridArea.BOTTOM_RIGHT
				else -> null
			}

			else -> null
		}
	}

	interface OnGridTouchListener {

		fun onGridTouch(area: TapGridArea, horizontalFraction: Float): Boolean

		fun onGridLongTouch(area: TapGridArea): Boolean

		fun onProcessTouch(rawX: Int, rawY: Int): Boolean

		fun isReaderScrollIdle(): Boolean = true
	}

	companion object {
		private const val LONG_PRESS_DELAY_MS = 450L
	}
}
