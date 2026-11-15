package org.koitharu.kotatsu.reader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.ReaderScrubberPosition
import org.koitharu.kotatsu.reader.ui.pager.ReaderUiState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val SlateGlassFill = Color(0xD91E2532)
private val SlateGlassBorder = Color(0x26FFFFFF)
private val NemuriCyan = Color(0xFF5CE1E6)
private val ObsidianCardFill = Color(0xF20F141E)

data class ChapterTransitionBannerState(
	val previousChapterTitle: String,
	val nextChapterTitle: String,
	val missingChaptersGap: Float? = null,
)

/**
 * Passive, 100% touch-passthrough HUD layer for:
 * 1. Bedtime Warm Amber & Night Dimmer screen filter
 * 2. Outlined Bottom-Center Page Counter (`2/56`) anchored at `Alignment.BottomCenter` (`12.dp` inset)
 */
@Composable
fun ReaderPassiveHudOverlay(
	uiState: ReaderUiState?,
	isPagesNumbersEnabled: Boolean,
	isBottomToolbarVisible: Boolean,
	bottomSystemInsetPx: Int,
	isWarmAmberEnabled: Boolean,
	warmAmberWarmth: Float,
	nightDimmerAlpha: Float,
) {
	val density = LocalDensity.current
	val bottomInsetDp = with(density) { bottomSystemInsetPx.toDp() }

	Box(modifier = Modifier.fillMaxSize()) {
		if (isWarmAmberEnabled && (warmAmberWarmth > 0.005f || nightDimmerAlpha > 0.005f)) {
			Canvas(modifier = Modifier.fillMaxSize()) {
				val amberAlpha = (warmAmberWarmth.coerceIn(0f, 1f) * 0.28f)
				if (amberAlpha > 0.002f) {
					drawRect(color = Color(0xFFFFA726).copy(alpha = amberAlpha))
					drawRect(color = Color(0xFF7C3F00).copy(alpha = amberAlpha * 0.35f))
				}
				val dimAlpha = nightDimmerAlpha.coerceIn(0f, 0.65f)
				if (dimAlpha > 0.002f) {
					drawRect(color = Color.Black.copy(alpha = dimAlpha))
				}
			}
		}

		val showCounter = isPagesNumbersEnabled &&
			!isBottomToolbarVisible &&
			uiState != null &&
			uiState.totalPages > 0 &&
			!(uiState.isEpub && !uiState.isEpubPaged)

		if (showCounter && uiState != null) {
			val currentDisplay = (uiState.currentPage + 1).coerceIn(1, uiState.totalPages)
			val counterText = "$currentDisplay/${uiState.totalPages}"
			OutlinedPageCounterText(
				text = counterText,
				modifier = Modifier
					.align(Alignment.BottomCenter)
					.padding(bottom = bottomInsetDp + 12.dp),
			)
		}
	}
}

@Composable
private fun OutlinedPageCounterText(
	text: String,
	modifier: Modifier = Modifier,
) {
	val baseStyle = TextStyle(
		fontSize = 12.sp,
		fontWeight = FontWeight.Bold,
		letterSpacing = 0.4.sp,
	)
	Box(modifier = modifier, contentAlignment = Alignment.Center) {
		Text(
			text = text,
			style = baseStyle.copy(
				color = Color(0xEB000000),
				drawStyle = Stroke(
					width = 4.5f,
					join = StrokeJoin.Round,
				),
			),
		)
		Text(
			text = text,
			style = baseStyle.copy(
				color = Color.White,
				shadow = Shadow(
					color = Color(0xCC000000),
					offset = Offset(0f, 1.5f),
					blurRadius = 3f,
				),
			),
		)
	}
}

/**
 * Interactive 3-piece vertical page scrubber capsule (`40.dp` width, `#D91E2532` slate-glass,
 * `1.dp` `#26FFFFFF` border) for `ReaderScrubberPosition.LEFT` and `ReaderScrubberPosition.RIGHT`.
 */
@Composable
fun ReaderVerticalScrubberCapsule(
	uiState: ReaderUiState?,
	position: ReaderScrubberPosition,
	isSliderSmooth: Boolean,
	onSelectPage: (Int) -> Unit,
	onPrevChapter: () -> Unit,
	onNextChapter: () -> Unit,
	modifier: Modifier = Modifier,
) {
	if (uiState == null || !uiState.isSliderAvailable()) return

	val totalPages = uiState.totalPages.coerceAtLeast(1)
	val currentPage = uiState.currentPage.coerceIn(0, totalPages - 1)
	val haptic = LocalHapticFeedback.current
	val density = LocalDensity.current

	var trackHeightPx by remember { mutableIntStateOf(1) }
	var isDragging by remember { mutableStateOf(false) }
	var dragFraction by remember { mutableFloatStateOf(0f) }
	var lastTickPage by remember { mutableIntStateOf(-1) }

	val restingFraction = if (totalPages <= 1) {
		0f
	} else {
		currentPage.toFloat() / (totalPages - 1).toFloat()
	}
	val activeFraction = if (isDragging) dragFraction else restingFraction
	val previewPageIndex = if (totalPages <= 1) {
		0
	} else {
		(activeFraction * (totalPages - 1)).roundToInt().coerceIn(0, totalPages - 1)
	}

	val pillShape = RoundedCornerShape(20.dp)

	Row(
		modifier = modifier,
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (position == ReaderScrubberPosition.RIGHT && isDragging && !isSliderSmooth) {
			ScrubberFloatingPageBadge(
				current = previewPageIndex + 1,
				total = totalPages,
				modifier = Modifier.padding(end = 10.dp),
			)
		}

		Column(
			modifier = Modifier
				.width(40.dp)
				.clip(pillShape)
				.background(SlateGlassFill, pillShape)
				.border(1.dp, SlateGlassBorder, pillShape)
				.padding(vertical = 6.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Box(
				modifier = Modifier
					.size(32.dp)
					.clip(CircleShape)
					.clickable(
						enabled = uiState.hasPreviousChapter(),
						onClick = {
							haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
							onPrevChapter()
						},
					),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					painter = painterResource(R.drawable.ic_prev),
					contentDescription = stringResource(R.string.prev_chapter),
					tint = if (uiState.hasPreviousChapter()) Color(0xFFF1F5F9) else Color(0x5594A3B8),
					modifier = Modifier.size(18.dp),
				)
			}

			if (!isSliderSmooth) {
				Text(
					text = "${previewPageIndex + 1}",
					color = NemuriCyan,
					fontSize = 11.sp,
					fontWeight = FontWeight.Bold,
					maxLines = 1,
				)
			}

			Spacer(modifier = Modifier.height(6.dp))

			Box(
				modifier = Modifier
					.width(32.dp)
					.height(172.dp)
					.onSizeChanged { trackHeightPx = it.height.coerceAtLeast(1) }
					.pointerInput(totalPages, isSliderSmooth) {
						detectTapGestures { offset ->
							if (totalPages <= 1) return@detectTapGestures
							val frac = (offset.y / trackHeightPx.toFloat()).coerceIn(0f, 1f)
							val target = (frac * (totalPages - 1)).roundToInt().coerceIn(0, totalPages - 1)
							haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
							onSelectPage(target)
						}
					}
					.pointerInput(totalPages, isSliderSmooth) {
						detectVerticalDragGestures(
							onDragStart = { startOffset ->
								isDragging = true
								dragFraction = (startOffset.y / trackHeightPx.toFloat()).coerceIn(0f, 1f)
								lastTickPage = (dragFraction * (totalPages - 1)).roundToInt()
							},
							onVerticalDrag = { change, _ ->
								change.consume()
								val frac = (change.position.y / trackHeightPx.toFloat()).coerceIn(0f, 1f)
								dragFraction = frac
								val page = (frac * (totalPages - 1)).roundToInt().coerceIn(0, totalPages - 1)
								if (isSliderSmooth) {
									onSelectPage(page)
								} else if (page != lastTickPage) {
									lastTickPage = page
									haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
								}
							},
							onDragEnd = {
								val finalPage = (dragFraction * (totalPages - 1)).roundToInt().coerceIn(0, totalPages - 1)
								isDragging = false
								onSelectPage(finalPage)
							},
							onDragCancel = {
								isDragging = false
							},
						)
					},
				contentAlignment = Alignment.TopCenter,
			) {
				// Track background
				Box(
					modifier = Modifier
						.fillMaxHeight()
						.width(4.dp)
						.clip(CircleShape)
						.background(Color(0x38FFFFFF)),
				)
				// Active cyan fill
				Box(
					modifier = Modifier
						.fillMaxHeight(activeFraction.coerceIn(0f, 1f))
						.width(4.dp)
						.clip(CircleShape)
						.background(NemuriCyan),
				)
				// Thumb knob
				val thumbSizePx = with(density) { 14.dp.roundToPx() }
				val usableTrackPx = (trackHeightPx - thumbSizePx).coerceAtLeast(0)
				val thumbOffsetY = (activeFraction * usableTrackPx).roundToInt()
				Box(
					modifier = Modifier
						.offset { IntOffset(0, thumbOffsetY) }
						.size(14.dp)
						.clip(CircleShape)
						.background(Color.White)
						.border(2.dp, NemuriCyan, CircleShape),
				)
			}

			Spacer(modifier = Modifier.height(6.dp))

			if (!isSliderSmooth) {
				Text(
					text = "$totalPages",
					color = Color(0xFF94A3B8),
					fontSize = 10.sp,
					fontWeight = FontWeight.SemiBold,
					maxLines = 1,
				)
			}

			Box(
				modifier = Modifier
					.size(32.dp)
					.clip(CircleShape)
					.clickable(
						enabled = uiState.hasNextChapter(),
						onClick = {
							haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
							onNextChapter()
						},
					),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					painter = painterResource(R.drawable.ic_action_skip),
					contentDescription = stringResource(R.string.next_chapter),
					tint = if (uiState.hasNextChapter()) Color(0xFFF1F5F9) else Color(0x5594A3B8),
					modifier = Modifier.size(18.dp),
				)
			}
		}

		if (position == ReaderScrubberPosition.LEFT && isDragging && !isSliderSmooth) {
			ScrubberFloatingPageBadge(
				current = previewPageIndex + 1,
				total = totalPages,
				modifier = Modifier.padding(start = 10.dp),
			)
		}
	}
}

@Composable
private fun ScrubberFloatingPageBadge(
	current: Int,
	total: Int,
	modifier: Modifier = Modifier,
) {
	val shape = RoundedCornerShape(12.dp)
	Box(
		modifier = modifier
			.clip(shape)
			.background(ObsidianCardFill, shape)
			.border(1.dp, SlateGlassBorder, shape)
			.padding(horizontal = 10.dp, vertical = 6.dp),
	) {
		Text(
			text = "$current / $total",
			color = Color.White,
			fontSize = 12.sp,
			fontWeight = FontWeight.Bold,
		)
	}
}

/**
 * Sleek chapter transition interstitial card displayed when `Show Chapter Transitions` is ON.
 * When `Show Chapter Transitions` is OFF, this overlay is bypassed completely and only the
 * floating bottom capsule toast (`ReaderToastView`) fires.
 */
@Composable
fun ReaderChapterTransitionOverlay(
	state: ChapterTransitionBannerState?,
	onDismiss: () -> Unit,
) {
	AnimatedVisibility(
		visible = state != null,
		enter = fadeIn(),
		exit = fadeOut(),
	) {
		val banner = state ?: return@AnimatedVisibility
		val interactionSource = remember { MutableInteractionSource() }
		val cardShape = RoundedCornerShape(20.dp)

		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(Color(0x88090C10))
				.clickable(
					interactionSource = interactionSource,
					indication = null,
					onClick = onDismiss,
				)
				.padding(horizontal = 28.dp),
			contentAlignment = Alignment.Center,
		) {
			Surface(
				shape = cardShape,
				color = ObsidianCardFill,
				modifier = Modifier
					.widthIn(max = 360.dp)
					.fillMaxWidth()
					.border(1.dp, SlateGlassBorder, cardShape)
					.clickable(
						interactionSource = remember { MutableInteractionSource() },
						indication = null,
						onClick = onDismiss,
					),
			) {
				Column(
					modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
					horizontalAlignment = Alignment.CenterHorizontally,
					verticalArrangement = Arrangement.spacedBy(10.dp),
				) {
					Text(
						text = stringResource(
							R.string.reader_chapter_transition_finished,
							banner.previousChapterTitle,
						),
						color = Color(0xFF94A3B8),
						fontSize = 13.sp,
						fontWeight = FontWeight.Medium,
						textAlign = TextAlign.Center,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)

					val gap = banner.missingChaptersGap
					if (gap != null && gap > 1.5f) {
						val gapText = formatGapCount(gap - 1f)
						Box(
							modifier = Modifier
								.clip(RoundedCornerShape(10.dp))
								.background(Color(0x26F59E0B))
								.border(1.dp, Color(0x55F59E0B), RoundedCornerShape(10.dp))
								.padding(horizontal = 12.dp, vertical = 5.dp),
						) {
							Text(
								text = stringResource(R.string.reader_chapter_transition_gap_warning, gapText),
								color = Color(0xFFFBBF24),
								fontSize = 12.sp,
								fontWeight = FontWeight.SemiBold,
								textAlign = TextAlign.Center,
							)
						}
					}

					Text(
						text = stringResource(
							R.string.reader_chapter_transition_next,
							banner.nextChapterTitle,
						),
						color = Color.White,
						fontSize = 15.sp,
						fontWeight = FontWeight.Bold,
						textAlign = TextAlign.Center,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)

					Spacer(modifier = Modifier.height(2.dp))

					Box(
						modifier = Modifier
							.clip(RoundedCornerShape(50))
							.background(NemuriCyan.copy(alpha = 0.16f))
							.border(1.dp, NemuriCyan.copy(alpha = 0.45f), RoundedCornerShape(50))
							.clickable(onClick = onDismiss)
							.padding(horizontal = 18.dp, vertical = 7.dp),
					) {
						Text(
							text = stringResource(R.string.reader_chapter_transition_tap_hint),
							color = NemuriCyan,
							fontSize = 12.sp,
							fontWeight = FontWeight.SemiBold,
						)
					}
				}
			}
		}
	}
}

private fun formatGapCount(gap: Float): String {
	val rounded = gap.roundToInt()
	return if (abs(gap - rounded.toFloat()) < 0.15f) {
		rounded.coerceAtLeast(1).toString()
	} else {
		String.format(Locale.US, "%.1f", gap)
	}
}
