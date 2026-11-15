package org.koitharu.kotatsu.enhance.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.parser.favicon.faviconUri
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.enhance.domain.PrioritySourceCandidate
import java.util.Locale

private val SelectorObsidianBg = Color(0xFF090C10)
private val SelectorCardSurface = Color(0xFF12161F)
private val SelectorCyan = Color(0xFF5CE1E6)
private val SelectorDarkInk = Color(0xFF061318)
private val SelectorMuted = Color(0xFF94A3B8)

@Composable
fun MihonSourcePrioritySelectorDialog(
	candidates: List<PrioritySourceCandidate>,
	initialSelectedKeys: List<String>,
	imageLoader: ImageLoader,
	onConfirm: (List<String>) -> Unit,
	onDismiss: () -> Unit,
) {
	val byKey = remember(candidates) { candidates.associateBy { it.key } }
	var selectedOrder by remember(candidates, initialSelectedKeys) {
		val validInitial = initialSelectedKeys.filter { it in byKey }
		mutableStateOf(
			if (validInitial.isNotEmpty() || initialSelectedKeys.isNotEmpty()) {
				validInitial
			} else {
				candidates.map { it.key }
			},
		)
	}

	val selectedSet = remember(selectedOrder) { selectedOrder.toSet() }
	val selectedItems = remember(selectedOrder, byKey) {
		selectedOrder.mapNotNull { byKey[it] }
	}
	val availableItems = remember(candidates, selectedSet) {
		candidates.filterNot { it.key in selectedSet }
	}

	val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
	val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(
			usePlatformDefaultWidth = false,
			decorFitsSystemWindows = false,
		),
	) {
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(SelectorObsidianBg),
		) {
			Column(
				modifier = Modifier
					.fillMaxSize()
					.padding(top = topInset),
			) {
				// Top Obsidian Toolbar (Select All, Deselect All, A-Z Sort)
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 8.dp, vertical = 10.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					IconButton(onClick = onDismiss) {
						Icon(
							painter = painterResource(R.drawable.ic_arrow_back),
							contentDescription = stringResource(R.string.close),
							tint = Color.White,
						)
					}
					Column(
						modifier = Modifier
							.weight(1f)
							.padding(horizontal = 6.dp),
					) {
						Text(
							text = stringResource(R.string.mihon_selector_title),
							style = MaterialTheme.typography.titleMedium,
							fontWeight = FontWeight.Bold,
							color = Color.White,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						Text(
							text = stringResource(R.string.mihon_selector_subtitle, selectedItems.size),
							style = MaterialTheme.typography.labelSmall,
							color = SelectorMuted,
							maxLines = 1,
						)
					}
					// Select All
					IconButton(
						onClick = {
							val missing = candidates.map { it.key }.filterNot { it in selectedSet }
							selectedOrder = selectedOrder + missing
						},
					) {
						Icon(
							painter = painterResource(R.drawable.ic_check),
							contentDescription = stringResource(R.string.mihon_selector_select_all),
							tint = SelectorCyan,
						)
					}
					// Deselect All
					IconButton(
						onClick = { selectedOrder = emptyList() },
					) {
						Icon(
							painter = painterResource(R.drawable.ic_close),
							contentDescription = stringResource(R.string.mihon_selector_deselect_all),
							tint = SelectorMuted,
						)
					}
					// A-Z Sort Selected
					IconButton(
						onClick = {
							selectedOrder = selectedItems
								.sortedBy { it.displayName.lowercase(Locale.ROOT) }
								.map { it.key }
						},
					) {
						Icon(
							painter = painterResource(R.drawable.ic_sort_by_name),
							contentDescription = stringResource(R.string.mihon_selector_sort_az),
							tint = Color.White,
						)
					}
				}

				LazyColumn(
					modifier = Modifier.fillMaxSize(),
					contentPadding = PaddingValues(
						start = 16.dp,
						end = 16.dp,
						top = 6.dp,
						bottom = bottomInset + 92.dp,
					),
					verticalArrangement = Arrangement.spacedBy(12.dp),
				) {
					// SELECTED SECTION
					item(key = "header_selected") {
						SectionLabel(
							title = stringResource(R.string.mihon_selector_selected_header),
							count = selectedItems.size,
							accent = SelectorCyan,
						)
					}

					item(key = "card_selected") {
						Surface(
							shape = RoundedCornerShape(18.dp),
							color = SelectorCardSurface,
							border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
							modifier = Modifier.fillMaxWidth(),
						) {
							if (selectedItems.isEmpty()) {
								Text(
									text = stringResource(R.string.mihon_selector_empty_selected),
									style = MaterialTheme.typography.bodySmall,
									color = SelectorMuted,
									modifier = Modifier.padding(18.dp),
								)
							} else {
								Column(modifier = Modifier.fillMaxWidth()) {
									selectedItems.forEachIndexed { idx, candidate ->
										SelectedPriorityRow(
											candidate = candidate,
											rank = idx + 1,
											canMoveUp = idx > 0,
											canMoveDown = idx < selectedItems.lastIndex,
											imageLoader = imageLoader,
											onToggleUncheck = {
												selectedOrder = selectedOrder.filterNot { it == candidate.key }
											},
											onMoveUp = {
												if (idx > 0) {
													val mut = selectedOrder.toMutableList()
													val item = mut.removeAt(idx)
													mut.add(idx - 1, item)
													selectedOrder = mut
												}
											},
											onMoveDown = {
												if (idx < selectedItems.lastIndex) {
													val mut = selectedOrder.toMutableList()
													val item = mut.removeAt(idx)
													mut.add(idx + 1, item)
													selectedOrder = mut
												}
											},
										)
										if (idx < selectedItems.lastIndex) {
											HorizontalDivider(
												color = Color.White.copy(alpha = 0.06f),
												modifier = Modifier.padding(horizontal = 14.dp),
											)
										}
									}
								}
							}
						}
					}

					// AVAILABLE SECTION
					item(key = "header_available") {
						Spacer(Modifier.height(4.dp))
						SectionLabel(
							title = stringResource(R.string.mihon_selector_available_header),
							count = availableItems.size,
							accent = SelectorMuted,
						)
					}

					item(key = "card_available") {
						Surface(
							shape = RoundedCornerShape(18.dp),
							color = SelectorCardSurface,
							border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
							modifier = Modifier.fillMaxWidth(),
						) {
							if (availableItems.isEmpty()) {
								Text(
									text = stringResource(R.string.mihon_selector_empty_available),
									style = MaterialTheme.typography.bodySmall,
									color = SelectorMuted,
									modifier = Modifier.padding(18.dp),
								)
							} else {
								Column(modifier = Modifier.fillMaxWidth()) {
									availableItems.forEachIndexed { idx, candidate ->
										AvailablePriorityRow(
											candidate = candidate,
											imageLoader = imageLoader,
											onSelect = {
												selectedOrder = selectedOrder + candidate.key
											},
										)
										if (idx < availableItems.lastIndex) {
											HorizontalDivider(
												color = Color.White.copy(alpha = 0.06f),
												modifier = Modifier.padding(horizontal = 14.dp),
											)
										}
									}
								}
							}
						}
					}
				}
			}

			// Bottom-right Continue FAB
			ExtendedFloatingActionButton(
				onClick = { onConfirm(selectedOrder) },
				containerColor = SelectorCyan,
				contentColor = SelectorDarkInk,
				shape = RoundedCornerShape(20.dp),
				modifier = Modifier
					.align(Alignment.BottomEnd)
					.padding(end = 20.dp, bottom = bottomInset + 20.dp),
				icon = {
					Icon(
						painter = painterResource(R.drawable.ic_arrow_forward),
						contentDescription = null,
						modifier = Modifier.size(20.dp),
					)
				},
				text = {
					Text(
						text = stringResource(R.string.mihon_selector_continue),
						fontWeight = FontWeight.Bold,
					)
				},
			)
		}
	}
}

@Composable
private fun SectionLabel(
	title: String,
	count: Int,
	accent: Color,
) {
	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		modifier = Modifier.padding(horizontal = 4.dp),
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.titleSmall,
			fontWeight = FontWeight.Bold,
			color = accent,
		)
		Surface(
			shape = RoundedCornerShape(50),
			color = accent.copy(alpha = 0.16f),
		) {
			Text(
				text = count.toString(),
				style = MaterialTheme.typography.labelSmall,
				fontWeight = FontWeight.Bold,
				color = accent,
				modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
			)
		}
	}
}

@Composable
private fun SelectedPriorityRow(
	candidate: PrioritySourceCandidate,
	rank: Int,
	canMoveUp: Boolean,
	canMoveDown: Boolean,
	imageLoader: ImageLoader,
	onToggleUncheck: () -> Unit,
	onMoveUp: () -> Unit,
	onMoveDown: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onToggleUncheck)
			.padding(horizontal = 10.dp, vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Checkbox(
			checked = true,
			onCheckedChange = { onToggleUncheck() },
			colors = CheckboxDefaults.colors(
				checkedColor = SelectorCyan,
				checkmarkColor = SelectorDarkInk,
			),
		)
		SourceFaviconAvatar(candidate = candidate, imageLoader = imageLoader)
		Spacer(Modifier.width(10.dp))
		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = candidate.displayName,
				style = MaterialTheme.typography.bodyMedium,
				fontWeight = FontWeight.SemiBold,
				color = Color.White,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = "#$rank • ${candidate.langBadge}",
				style = MaterialTheme.typography.labelSmall,
				color = SelectorMuted,
				fontSize = 11.sp,
			)
		}
		IconButton(
			onClick = onMoveUp,
			enabled = canMoveUp,
			modifier = Modifier.size(34.dp),
		) {
			Icon(
				painter = painterResource(R.drawable.ic_arrow_up),
				contentDescription = null,
				tint = if (canMoveUp) Color.White else SelectorMuted.copy(alpha = 0.28f),
				modifier = Modifier.size(18.dp),
			)
		}
		IconButton(
			onClick = onMoveDown,
			enabled = canMoveDown,
			modifier = Modifier.size(34.dp),
		) {
			Icon(
				painter = painterResource(R.drawable.ic_arrow_up),
				contentDescription = null,
				tint = if (canMoveDown) Color.White else SelectorMuted.copy(alpha = 0.28f),
				modifier = Modifier
					.size(18.dp)
					.rotate(180f),
			)
		}
		Text(
			text = "≡",
			fontSize = 18.sp,
			fontWeight = FontWeight.Bold,
			color = SelectorMuted,
			modifier = Modifier.padding(horizontal = 6.dp),
		)
	}
}

@Composable
private fun AvailablePriorityRow(
	candidate: PrioritySourceCandidate,
	imageLoader: ImageLoader,
	onSelect: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onSelect)
			.padding(horizontal = 10.dp, vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Checkbox(
			checked = false,
			onCheckedChange = { onSelect() },
			colors = CheckboxDefaults.colors(
				uncheckedColor = SelectorMuted,
			),
		)
		SourceFaviconAvatar(candidate = candidate, imageLoader = imageLoader)
		Spacer(Modifier.width(10.dp))
		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = candidate.displayName,
				style = MaterialTheme.typography.bodyMedium,
				fontWeight = FontWeight.Medium,
				color = Color(0xFFD5DAE5),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = candidate.langBadge,
				style = MaterialTheme.typography.labelSmall,
				color = SelectorMuted,
				fontSize = 11.sp,
			)
		}
	}
}

@Composable
private fun SourceFaviconAvatar(
	candidate: PrioritySourceCandidate,
	imageLoader: ImageLoader,
) {
	val context = LocalContext.current
	val request = remember(context, candidate.source) {
		ImageRequest.Builder(context)
			.data(candidate.source.faviconUri())
			.mangaSourceExtra(candidate.source)
			.build()
	}
	val fallback = painterResource(R.drawable.ic_web)
	Surface(
		shape = RoundedCornerShape(8.dp),
		color = Color(0xFF0D1118),
		border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
		modifier = Modifier.size(30.dp),
	) {
		Box(contentAlignment = Alignment.Center) {
			AsyncImage(
				model = request,
				imageLoader = imageLoader,
				contentDescription = null,
				placeholder = fallback,
				error = fallback,
				fallback = fallback,
				contentScale = ContentScale.Fit,
				modifier = Modifier
					.size(20.dp)
					.clip(RoundedCornerShape(4.dp)),
			)
		}
	}
}
