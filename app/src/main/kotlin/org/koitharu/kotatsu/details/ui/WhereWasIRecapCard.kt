package org.koitharu.kotatsu.details.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.entity.MangaAiRecapEntity
import org.koitharu.kotatsu.details.domain.WhereWasIRecapUseCase

private val RecapCardSurface = Color(0xFF12161F)
private val RecapInnerPill = Color(0xFF0D1118)
private val RecapCyan = Color(0xFF5CE1E6)
private val RecapBodyColor = Color(0xFFE2E8F0)
private val RecapMuted = Color(0xFF94A3B8)
private val RecapErrorRose = Color(0xFFF87171)

data class WhereWasIRecapUiState(
	val isVisible: Boolean = false,
	val isGenerating: Boolean = false,
	val recap: MangaAiRecapEntity? = null,
	val fallbackChapterNumber: Float = -1f,
	val errorMessage: String? = null,
)

/**
 * Closeable, strictly spoiler-locked `✦ Where Was I?` recap card mounted on `DetailsExpressiveScreen`
 * whenever Generative AI is enabled and the user has `>= 1` read chapter on the series.
 *
 * - Tapping `✕` persists `isDismissed = true` for the current `upToChapterId` in Room and collapses
 *   the card into a subtle compact **`✦ Where Was I?`** pill chip.
 * - Tapping the compact chip (or advancing to a newer chapter in the reader) restores the full card.
 */
@Composable
fun WhereWasIRecapCard(
	state: WhereWasIRecapUiState,
	onRegenerate: () -> Unit,
	onDismiss: () -> Unit,
	onReopen: () -> Unit,
	onOpenAiSettings: () -> Unit,
	modifier: Modifier = Modifier,
) {
	if (!state.isVisible) return

	val chapterNum = state.recap?.upToChapterNumber ?: state.fallbackChapterNumber
	val chapterBadgeNumber = WhereWasIRecapUseCase.formatChapterNumber(chapterNum)
	val isCollapsed = state.recap?.isDismissed == true && !state.isGenerating

	Box(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = SCREEN_PADDING, vertical = 6.dp),
	) {
		AnimatedContent(
			targetState = isCollapsed,
			transitionSpec = { fadeIn() togetherWith fadeOut() },
			label = "WhereWasIRecapCollapse",
		) { collapsed ->
			if (collapsed) {
				CollapsedWhereWasIChip(
					chapterNumberText = chapterBadgeNumber,
					onClick = onReopen,
				)
			} else {
				ExpandedWhereWasICard(
					state = state,
					chapterNumberText = chapterBadgeNumber,
					onRegenerate = onRegenerate,
					onDismiss = onDismiss,
					onOpenAiSettings = onOpenAiSettings,
				)
			}
		}
	}
}

@Composable
private fun CollapsedWhereWasIChip(
	chapterNumberText: String,
	onClick: () -> Unit,
) {
	Row(modifier = Modifier.fillMaxWidth()) {
		Surface(
			shape = RoundedCornerShape(50),
			color = RecapCardSurface,
			border = BorderStroke(1.dp, RecapCyan.copy(alpha = 0.38f)),
			modifier = Modifier
				.clip(RoundedCornerShape(50))
				.clickable(onClick = onClick),
		) {
			Row(
				modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				Icon(
					painter = painterResource(R.drawable.ic_sparkles),
					contentDescription = null,
					tint = RecapCyan,
					modifier = Modifier.size(14.dp),
				)
				Text(
					text = stringResource(R.string.where_was_i_title),
					style = MaterialTheme.typography.labelMedium,
					fontWeight = FontWeight.Bold,
					color = Color.White,
					maxLines = 1,
					softWrap = false,
				)
				Text(
					text = "• Ch. $chapterNumberText",
					style = MaterialTheme.typography.labelSmall,
					color = RecapCyan,
					maxLines = 1,
					softWrap = false,
				)
			}
		}
	}
}

@Composable
private fun ExpandedWhereWasICard(
	state: WhereWasIRecapUiState,
	chapterNumberText: String,
	onRegenerate: () -> Unit,
	onDismiss: () -> Unit,
	onOpenAiSettings: () -> Unit,
) {
	Surface(
		shape = RoundedCornerShape(18.dp),
		color = RecapCardSurface,
		border = BorderStroke(1.dp, RecapCyan.copy(alpha = 0.28f)),
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 14.dp),
		) {
			// Header row: [✦ Where Was I?] on left + [⚙] [↻] [✕] action pills on right
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					modifier = Modifier.weight(1f),
				) {
					Icon(
						painter = painterResource(R.drawable.ic_sparkles),
						contentDescription = null,
						tint = RecapCyan,
						modifier = Modifier.size(17.dp),
					)
					Text(
						text = stringResource(R.string.where_was_i_title),
						style = MaterialTheme.typography.titleSmall,
						fontWeight = FontWeight.Bold,
						color = Color.White,
						maxLines = 1,
						softWrap = false,
					)
				}

				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(4.dp),
				) {
					RecapHeaderIconPill(
						iconRes = R.drawable.ic_settings,
						contentDescription = stringResource(R.string.ai_engine_settings_title),
						tint = RecapMuted,
						onClick = onOpenAiSettings,
					)
					RecapHeaderIconPill(
						iconRes = R.drawable.ic_restart,
						contentDescription = stringResource(R.string.where_was_i_regenerate),
						tint = if (state.isGenerating) RecapCyan.copy(alpha = 0.4f) else RecapCyan,
						enabled = !state.isGenerating,
						onClick = onRegenerate,
					)
					if (state.recap != null) {
						RecapHeaderIconPill(
							iconRes = R.drawable.ic_close_small,
							contentDescription = stringResource(R.string.where_was_i_dismiss),
							tint = RecapMuted,
							onClick = onDismiss,
						)
					}
				}
			}

			Spacer(Modifier.height(8.dp))

			// Non-wrapping Spoiler-Free • Ch. X badge + active backend attribution
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Surface(
					shape = RoundedCornerShape(50),
					color = RecapCyan.copy(alpha = 0.14f),
					border = BorderStroke(1.dp, RecapCyan.copy(alpha = 0.35f)),
				) {
					Text(
						text = stringResource(R.string.where_was_i_spoiler_badge, chapterNumberText),
						style = MaterialTheme.typography.labelSmall,
						fontWeight = FontWeight.Bold,
						fontSize = 11.sp,
						color = RecapCyan,
						maxLines = 1,
						softWrap = false,
						modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
					)
				}

				val backendLabel = state.recap?.backendUsed
				if (!backendLabel.isNullOrBlank()) {
					Text(
						text = backendLabel,
						style = MaterialTheme.typography.labelSmall,
						color = RecapMuted,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.weight(1f, fill = false),
					)
				}
			}

			Spacer(Modifier.height(10.dp))
			HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
			Spacer(Modifier.height(10.dp))

			when {
				state.isGenerating && state.recap == null -> {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(10.dp),
						modifier = Modifier.padding(vertical = 6.dp),
					) {
						CircularProgressIndicator(
							color = RecapCyan,
							strokeWidth = 2.dp,
							modifier = Modifier.size(16.dp),
						)
						Text(
							text = "Synthesizing spoiler-locked recap up to Ch. $chapterNumberText…",
							style = MaterialTheme.typography.bodySmall,
							color = RecapMuted,
						)
					}
				}

				state.errorMessage != null && state.recap == null -> {
					Text(
						text = state.errorMessage,
						style = MaterialTheme.typography.bodySmall,
						color = RecapErrorRose,
					)
				}

				state.recap != null -> {
					Text(
						text = state.recap.summaryText,
						style = MaterialTheme.typography.bodyMedium,
						lineHeight = 20.sp,
						color = RecapBodyColor,
					)
					if (state.errorMessage != null) {
						Spacer(Modifier.height(6.dp))
						Text(
							text = state.errorMessage,
							style = MaterialTheme.typography.labelSmall,
							color = RecapErrorRose,
						)
					}
				}
			}
		}
	}
}

@Composable
private fun RecapHeaderIconPill(
	iconRes: Int,
	contentDescription: String,
	tint: Color,
	enabled: Boolean = true,
	onClick: () -> Unit,
) {
	Surface(
		shape = CircleShape,
		color = RecapInnerPill,
		border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
		modifier = Modifier
			.size(30.dp)
			.clip(CircleShape)
			.clickable(enabled = enabled, onClick = onClick),
	) {
		Box(contentAlignment = Alignment.Center) {
			Icon(
				painter = painterResource(iconRes),
				contentDescription = contentDescription,
				tint = tint,
				modifier = Modifier.size(15.dp),
			)
		}
	}
}
