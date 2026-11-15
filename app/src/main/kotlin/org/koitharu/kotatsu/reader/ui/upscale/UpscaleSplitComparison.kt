package org.koitharu.kotatsu.reader.ui.upscale

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.os.HardwareAcceleratorProbe
import org.koitharu.kotatsu.core.prefs.ReaderUpscalePreset
import org.koitharu.kotatsu.core.prefs.UpscaleComputeMode
import org.koitharu.kotatsu.reader.domain.ReaderBitmapUpscaler
import kotlin.math.max
import kotlin.math.roundToInt

private val NemuriObsidianCard = Color(0xFF12161F)
private val NemuriCyan = Color(0xFF5CE1E6)
private val NemuriDarkInk = Color(0xFF061318)

@Composable
fun UpscalePresetGridSelector(
	selectedPreset: ReaderUpscalePreset,
	onSelectPreset: (ReaderUpscalePreset) -> Unit,
	modifier: Modifier = Modifier,
) {
	val rows = remember {
		listOf(
			listOf(ReaderUpscalePreset.OFF, ReaderUpscalePreset.FAST_NPU_1_5X),
			listOf(ReaderUpscalePreset.HQ_LINE_ART_2X, ReaderUpscalePreset.BEDTIME_WARM_CONTRAST),
		)
	}
	Column(
		modifier = modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		rows.forEach { rowPresets ->
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				rowPresets.forEach { preset ->
					val selected = preset == selectedPreset
					Surface(
						shape = RoundedCornerShape(12.dp),
						color = if (selected) NemuriCyan.copy(alpha = 0.18f) else Color(0xFF0D1118),
						border = BorderStroke(
							width = 1.dp,
							color = if (selected) NemuriCyan else Color.White.copy(alpha = 0.09f),
						),
						modifier = Modifier
							.weight(1f)
							.clip(RoundedCornerShape(12.dp))
							.clickable { onSelectPreset(preset) },
					) {
						Text(
							text = stringResource(preset.titleResId),
							style = MaterialTheme.typography.labelMedium,
							fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
							fontSize = 12.sp,
							color = if (selected) NemuriCyan else Color(0xFFD5DAE5),
							textAlign = TextAlign.Center,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.padding(horizontal = 8.dp, vertical = 11.dp),
						)
					}
				}
			}
		}
	}
}

@Composable
fun UpscaleComputeModeSelector(
	selectedMode: UpscaleComputeMode,
	onSelectMode: (UpscaleComputeMode) -> Unit,
	modifier: Modifier = Modifier,
) {
	val resolvedBackend = remember(selectedMode) {
		HardwareAcceleratorProbe.resolveUpscaleBackend(selectedMode)
	}
	Column(
		modifier = modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Text(
				text = stringResource(R.string.upscale_compute_title),
				style = MaterialTheme.typography.labelMedium,
				fontWeight = FontWeight.SemiBold,
				color = Color(0xFF94A3B8),
			)
			Surface(
				shape = RoundedCornerShape(50),
				color = NemuriCyan.copy(alpha = 0.14f),
				border = BorderStroke(1.dp, NemuriCyan.copy(alpha = 0.40f)),
			) {
				Text(
					text = "Active: ${resolvedBackend.badgeLabel}",
					style = MaterialTheme.typography.labelSmall,
					fontSize = 10.sp,
					fontWeight = FontWeight.Bold,
					color = NemuriCyan,
					modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
				)
			}
		}
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			UpscaleComputeMode.entries.forEach { mode ->
				val selected = mode == selectedMode
				val shortLabelRes = when (mode) {
					UpscaleComputeMode.AUTO -> R.string.upscale_compute_auto_short
					UpscaleComputeMode.GPU -> R.string.upscale_compute_gpu_short
					UpscaleComputeMode.CPU -> R.string.upscale_compute_cpu_short
				}
				Surface(
					shape = RoundedCornerShape(10.dp),
					color = if (selected) NemuriCyan.copy(alpha = 0.18f) else Color(0xFF0D1118),
					border = BorderStroke(
						width = 1.dp,
						color = if (selected) NemuriCyan else Color.White.copy(alpha = 0.09f),
					),
					modifier = Modifier
						.weight(1f)
						.clip(RoundedCornerShape(10.dp))
						.clickable { onSelectMode(mode) },
				) {
					Text(
						text = stringResource(shortLabelRes),
						style = MaterialTheme.typography.labelSmall,
						fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
						fontSize = 11.sp,
						color = if (selected) NemuriCyan else Color(0xFFD5DAE5),
						textAlign = TextAlign.Center,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.padding(horizontal = 6.dp, vertical = 9.dp),
					)
				}
			}
		}
	}
}

@Composable
fun UpscaleSplitComparisonFrame(
	sourceBitmap: Bitmap?,
	selectedPreset: ReaderUpscalePreset,
	fallbackPreviewPreset: ReaderUpscalePreset,
	isLoading: Boolean,
	computeMode: UpscaleComputeMode = UpscaleComputeMode.AUTO,
	errorMessage: String? = null,
	aspectRatio: Float = 0.80f,
	modifier: Modifier = Modifier,
) {
	var splitRatio by remember { mutableFloatStateOf(0.5f) }
	var frameWidthPx by remember { mutableFloatStateOf(1f) }
	val density = LocalDensity.current
	val activeBackend = remember(computeMode) {
		HardwareAcceleratorProbe.resolveUpscaleBackend(computeMode)
	}

	val effectiveRightPreset = if (selectedPreset == ReaderUpscalePreset.OFF) {
		if (fallbackPreviewPreset == ReaderUpscalePreset.OFF) {
			ReaderUpscalePreset.FAST_NPU_1_5X
		} else {
			fallbackPreviewPreset
		}
	} else {
		selectedPreset
	}

	val processedPair by produceState<Pair<Bitmap, Bitmap>?>(
		initialValue = null,
		key1 = sourceBitmap,
		key2 = effectiveRightPreset to computeMode,
		key3 = isLoading,
	) {
		if (isLoading && sourceBitmap == null) {
			value = null
			return@produceState
		}
		value = withContext(Dispatchers.Default) {
			val raw = sourceBitmap ?: createProceduralMangaSampleBitmap()
			ReaderBitmapUpscaler.processForPreview(raw, effectiveRightPreset, computeMode)
		}
	}

	Column(
		modifier = modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.aspectRatio(aspectRatio)
				.clip(RoundedCornerShape(16.dp))
				.background(Color(0xFF0B0F16))
				.border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
				.onSizeChanged { frameWidthPx = it.width.toFloat().coerceAtLeast(1f) }
				.pointerInput(Unit) {
					detectTapGestures { offset ->
						splitRatio = (offset.x / frameWidthPx).coerceIn(0.08f, 0.92f)
					}
				}
				.pointerInput(Unit) {
					detectHorizontalDragGestures { change, _ ->
						change.consume()
						splitRatio = (change.position.x / frameWidthPx).coerceIn(0.08f, 0.92f)
					}
				},
		) {
			val pair = processedPair
			when {
				pair != null -> {
					val origImg = remember(pair.first) { pair.first.asImageBitmap() }
					val enhImg = remember(pair.second) { pair.second.asImageBitmap() }
					ComposeCanvas(modifier = Modifier.fillMaxSize()) {
						val canvasW = size.width
						val canvasH = size.height
						val imgW = origImg.width.toFloat().coerceAtLeast(1f)
						val imgH = origImg.height.toFloat().coerceAtLeast(1f)
						val scale = max(canvasW / imgW, canvasH / imgH)
						val dstW = (imgW * scale).roundToInt()
						val dstH = (imgH * scale).roundToInt()
						val dstOffsetX = ((canvasW - dstW) / 2f).roundToInt()
						val dstOffsetY = ((canvasH - dstH) / 2f).roundToInt()
						val dstOffset = IntOffset(dstOffsetX, dstOffsetY)
						val dstSize = IntSize(dstW, dstH)
						val splitX = canvasW * splitRatio

						clipRect(left = 0f, top = 0f, right = splitX, bottom = canvasH) {
							drawImage(
								image = origImg,
								dstOffset = dstOffset,
								dstSize = dstSize,
							)
						}
						clipRect(left = splitX, top = 0f, right = canvasW, bottom = canvasH) {
							drawImage(
								image = enhImg,
								dstOffset = dstOffset,
								dstSize = dstSize,
							)
						}
					}

					val handleXOffsetPx = (frameWidthPx * splitRatio).roundToInt()
					val halfHandlePx = with(density) { 18.dp.roundToPx() }
					val halfLinePx = with(density) { 1.dp.roundToPx() }

					Box(
						modifier = Modifier
							.offset { IntOffset(handleXOffsetPx - halfLinePx, 0) }
							.width(2.dp)
							.fillMaxHeight()
							.background(NemuriCyan),
					)

					Surface(
						color = NemuriCyan,
						shape = CircleShape,
						shadowElevation = 8.dp,
						modifier = Modifier
							.align(Alignment.CenterStart)
							.offset { IntOffset(handleXOffsetPx - halfHandlePx, 0) }
							.size(36.dp),
					) {
						Box(contentAlignment = Alignment.Center) {
							Icon(
								painter = painterResource(R.drawable.ic_swap),
								contentDescription = stringResource(R.string.upscale_split_drag_hint),
								tint = NemuriDarkInk,
								modifier = Modifier.size(18.dp),
							)
						}
					}
				}

				isLoading -> {
					Box(
						modifier = Modifier.fillMaxSize(),
						contentAlignment = Alignment.Center,
					) {
						CircularProgressIndicator(
							color = NemuriCyan,
							strokeWidth = 2.5.dp,
							modifier = Modifier.size(32.dp),
						)
					}
				}

				!errorMessage.isNullOrBlank() -> {
					Box(
						modifier = Modifier
							.fillMaxSize()
							.padding(20.dp),
						contentAlignment = Alignment.Center,
					) {
						Text(
							text = errorMessage,
							style = MaterialTheme.typography.bodySmall,
							color = Color(0xFF94A3B8),
							textAlign = TextAlign.Center,
						)
					}
				}
			}

			// Top-left Original badge
			Surface(
				color = NemuriObsidianCard.copy(alpha = 0.85f),
				shape = RoundedCornerShape(8.dp),
				border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
				modifier = Modifier
					.align(Alignment.TopStart)
					.padding(10.dp),
			) {
				Text(
					text = stringResource(R.string.upscale_preview_original),
					style = MaterialTheme.typography.labelSmall,
					fontWeight = FontWeight.SemiBold,
					color = Color(0xFFD5DAE5),
					modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
				)
			}

			// Top-right Active Preset + Compute Delegate badge
			Surface(
				color = NemuriObsidianCard.copy(alpha = 0.88f),
				shape = RoundedCornerShape(8.dp),
				border = BorderStroke(1.dp, NemuriCyan.copy(alpha = 0.55f)),
				modifier = Modifier
					.align(Alignment.TopEnd)
					.padding(10.dp),
			) {
				Text(
					text = "${stringResource(effectiveRightPreset.shortLabelResId)} • ${activeBackend.badgeLabel}",
					style = MaterialTheme.typography.labelSmall,
					fontWeight = FontWeight.Bold,
					color = NemuriCyan,
					modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
				)
			}
		}

		Text(
			text = stringResource(R.string.upscale_split_drag_hint),
			style = MaterialTheme.typography.labelSmall,
			color = Color(0xFF94A3B8),
			modifier = Modifier.padding(horizontal = 2.dp),
		)
	}
}

/**
 * Clean monochrome manga panel & screentone plate used ONLY when the user's library has 0 covers/pages.
 */
private fun createProceduralMangaSampleBitmap(): Bitmap {
	val w = 720
	val h = 900
	val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
	val canvas = Canvas(bmp)
	canvas.drawColor(0xFFF6F6F4.toInt())

	val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = 0xFF14161A.toInt()
		style = Paint.Style.STROKE
		strokeWidth = 6f
	}
	val thinInk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = 0xFF1E2229.toInt()
		style = Paint.Style.STROKE
		strokeWidth = 2.4f
	}
	val tonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = 0xFF6E7480.toInt()
		style = Paint.Style.FILL
	}

	// Panel borders
	canvas.drawRect(36f, 36f, w - 36f, 430f, borderPaint)
	canvas.drawRect(36f, 454f, w - 36f, h - 36f, borderPaint)

	// Screentone dot gradient in top panel
	for (y in 56..410 step 9) {
		val r = (1.1f + (y - 56) / 220f).coerceAtMost(2.8f)
		for (x in 56..(w - 56) step 9) {
			canvas.drawCircle(x.toFloat(), y.toFloat(), r, tonePaint)
		}
	}

	// Speed lines & crisp manga line-art curves in bottom panel
	val cx = w * 0.5f
	val cy = 670f
	for (i in 0 until 44) {
		val angle = (i * Math.PI * 2.0) / 44.0
		val x1 = cx + (Math.cos(angle) * 120.0).toFloat()
		val y1 = cy + (Math.sin(angle) * 110.0).toFloat()
		val x2 = cx + (Math.cos(angle) * 310.0).toFloat()
		val y2 = cy + (Math.sin(angle) * 210.0).toFloat()
		canvas.drawLine(x1, y1, x2.coerceIn(42f, w - 42f), y2.coerceIn(460f, h - 42f), thinInk)
	}

	// Speech bubble with crisp lettering
	val bubbleRect = RectF(74f, 82f, 360f, 214f)
	val bubbleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = 0xFFFFFFFF.toInt()
		style = Paint.Style.FILL
	}
	canvas.drawOval(bubbleRect, bubbleFill)
	canvas.drawOval(bubbleRect, borderPaint)

	val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = 0xFF101318.toInt()
		textSize = 30f
		isFakeBoldText = true
		textAlign = Paint.Align.CENTER
	}
	canvas.drawText("NEMURI", bubbleRect.centerX(), bubbleRect.centerY() - 6f, textPaint)
	textPaint.textSize = 21f
	textPaint.isFakeBoldText = false
	canvas.drawText("INK & SCREENTONE", bubbleRect.centerX(), bubbleRect.centerY() + 26f, textPaint)

	// Stylized eye line-art in bottom center
	val eyePath = Path().apply {
		moveTo(cx - 110f, cy)
		quadTo(cx, cy - 68f, cx + 110f, cy)
		quadTo(cx, cy + 54f, cx - 110f, cy)
	}
	canvas.drawPath(eyePath, bubbleFill)
	canvas.drawPath(eyePath, borderPaint)
	canvas.drawCircle(cx, cy - 4f, 32f, borderPaint)
	canvas.drawCircle(cx, cy - 4f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF101318.toInt() })

	return bmp
}
