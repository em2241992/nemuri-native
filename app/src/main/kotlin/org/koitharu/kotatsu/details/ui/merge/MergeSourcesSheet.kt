package org.koitharu.kotatsu.details.ui.merge

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.viewModels
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.entity.MergedMangaEntity
import org.koitharu.kotatsu.core.model.MangaSource as ResolveMangaSource
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import javax.inject.Inject

@AndroidEntryPoint
class MergeSourcesSheet : BottomSheetDialogFragment() {

	@Inject
	lateinit var imageLoader: ImageLoader

	private val viewModel: MergeSourcesViewModel by viewModels()

	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
		return (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).apply {
			setOnShowListener { dialogInterface ->
				val bottomSheet = (dialogInterface as? BottomSheetDialog)
					?.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
				bottomSheet?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
				if (bottomSheet != null) {
					BottomSheetBehavior.from(bottomSheet).apply {
						state = BottomSheetBehavior.STATE_EXPANDED
						skipCollapsed = true
					}
				}
			}
		}
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				val snackbarHostState = remember { SnackbarHostState() }
				LaunchedEffect(viewModel) {
					viewModel.onFeedbackMessage.collect { event ->
						event?.consume { message ->
							snackbarHostState.currentSnackbarData?.dismiss()
							snackbarHostState.showSnackbar(
								message = message,
								duration = SnackbarDuration.Short,
							)
						}
					}
				}
				MergeSourcesSheetContent(
					viewModel = viewModel,
					imageLoader = imageLoader,
					snackbarHostState = snackbarHostState,
					onClose = ::dismissAllowingStateLoss,
				)
			}
		}
	}
}

@Composable
private fun MergeSourcesSheetContent(
	viewModel: MergeSourcesViewModel,
	imageLoader: ImageLoader,
	snackbarHostState: SnackbarHostState,
	onClose: () -> Unit,
) {
	val context = LocalContext.current
	val focusManager = LocalFocusManager.current
	val scheme = MaterialTheme.colorScheme
	val mergedSources by viewModel.mergedSources.collectAsState()
	val availableSources by viewModel.availableSources.collectAsState()
	val selectedSourceName by viewModel.selectedSourceName.collectAsState()
	val query by viewModel.searchQuery.collectAsState()
	val searchResults by viewModel.searchResults.collectAsState()
	val isSearching by viewModel.isSearching.collectAsState()

	val linkedKeys = remember(mergedSources) {
		mergedSources.mapTo(HashSet()) { "${it.mergedSourceName}|${it.mergedMangaUrl}" }
	}

	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.fillMaxHeight(0.90f),
		shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
		color = scheme.background,
	) {
		Box(
			modifier = Modifier
				.fillMaxSize()
				.navigationBarsPadding(),
		) {
			Column(modifier = Modifier.fillMaxSize()) {
				// Drag handle
				Box(
					modifier = Modifier
						.padding(top = 10.dp, bottom = 6.dp)
						.size(width = 36.dp, height = 4.dp)
						.clip(CircleShape)
						.background(scheme.onSurfaceVariant.copy(alpha = 0.35f))
						.align(Alignment.CenterHorizontally),
				)

				// Sheet header
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 20.dp, vertical = 6.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Icon(
						painter = painterResource(R.drawable.ic_link),
						contentDescription = null,
						tint = scheme.primary,
						modifier = Modifier.size(22.dp),
					)
					Spacer(Modifier.width(10.dp))
					Column(modifier = Modifier.weight(1f)) {
						Text(
							text = stringResource(R.string.merge_sources),
							style = MaterialTheme.typography.titleLarge,
							fontWeight = FontWeight.Bold,
							color = scheme.onBackground,
						)
						Text(
							text = stringResource(R.string.merge_sources_subtitle),
							style = MaterialTheme.typography.bodySmall,
							color = scheme.onSurfaceVariant,
							maxLines = 2,
							overflow = TextOverflow.Ellipsis,
						)
					}
					IconButton(onClick = onClose) {
						Icon(
							painter = painterResource(R.drawable.ic_close),
							contentDescription = stringResource(android.R.string.cancel),
							tint = scheme.onSurfaceVariant,
						)
					}
				}

				LazyColumn(
					modifier = Modifier.fillMaxSize(),
					contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 84.dp),
					verticalArrangement = Arrangement.spacedBy(10.dp),
				) {
					// Primary series card
					item(key = "primary_card") {
						PrimaryMangaCard(
							manga = viewModel.primaryManga,
							sourceTitle = viewModel.primaryManga.source.getTitle(context),
							imageLoader = imageLoader,
						)
					}

					// Linked sources header
					item(key = "linked_header") {
						Text(
							text = stringResource(R.string.merge_linked_sources_header, mergedSources.size),
							style = MaterialTheme.typography.labelLarge,
							fontWeight = FontWeight.SemiBold,
							color = scheme.primary,
							modifier = Modifier.padding(top = 6.dp, start = 4.dp),
						)
					}

					if (mergedSources.isEmpty()) {
						item(key = "linked_empty") {
							Surface(
								shape = RoundedCornerShape(16.dp),
								color = scheme.surfaceContainerLow,
								border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.35f)),
								modifier = Modifier.fillMaxWidth(),
							) {
								Text(
									text = stringResource(R.string.merge_no_linked_sources),
									style = MaterialTheme.typography.bodyMedium,
									color = scheme.onSurfaceVariant,
									modifier = Modifier.padding(16.dp),
								)
							}
						}
					} else {
						itemsIndexed(
							items = mergedSources,
							key = { _, item -> "linked_${item.id}" },
						) { index, entity ->
							LinkedMergedSourceRow(
								entity = entity,
								priorityRank = index + 2,
								canMoveUp = index > 0,
								canMoveDown = index < mergedSources.lastIndex,
								sourceTitle = ResolveMangaSource(entity.mergedSourceName).getTitle(context),
								imageLoader = imageLoader,
								onToggleEnabled = { enabled -> viewModel.toggleEnabled(entity, enabled) },
								onMoveUp = { viewModel.movePriority(entity, moveUp = true) },
								onMoveDown = { viewModel.movePriority(entity, moveUp = false) },
								onUnlink = { viewModel.unlinkSource(entity) },
							)
						}
					}

					item(key = "divider") {
						HorizontalDivider(
							modifier = Modifier.padding(vertical = 6.dp),
							color = scheme.outlineVariant.copy(alpha = 0.35f),
						)
					}

					// Search & link header + search input + alt-title / source chips
					item(key = "search_controls") {
						SearchControlsSection(
							query = query,
							altTitles = viewModel.primaryManga.altTitles,
							availableSources = availableSources,
							selectedSourceName = selectedSourceName,
							isSearching = isSearching,
							onQueryChange = viewModel::updateQuery,
							onSubmitSearch = { q ->
								focusManager.clearFocus()
								viewModel.submitSearch(q)
							},
							onSelectSource = viewModel::selectSourceFilter,
						)
					}

					if (availableSources.isEmpty()) {
						item(key = "no_sources") {
							Text(
								text = stringResource(R.string.merge_no_enabled_sources),
								style = MaterialTheme.typography.bodyMedium,
								color = scheme.onSurfaceVariant,
								modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
							)
						}
					} else if (!isSearching && searchResults.isEmpty() && query.isNotBlank()) {
						item(key = "no_results") {
							Text(
								text = stringResource(R.string.merge_no_search_results, query.trim()),
								style = MaterialTheme.typography.bodyMedium,
								color = scheme.onSurfaceVariant,
								modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
							)
						}
					} else {
						items(
							items = searchResults,
							key = { manga -> "cand_${manga.source.name}_${manga.url}" },
						) { candidate ->
							val isAlreadyLinked = "${candidate.source.name}|${candidate.url}" in linkedKeys
							SearchCandidateRow(
								candidate = candidate,
								sourceTitle = candidate.source.getTitle(context),
								isLinked = isAlreadyLinked,
								imageLoader = imageLoader,
								onLink = { viewModel.linkCandidate(candidate) },
							)
						}
					}
				}
			}

			// Floating bottom capsule toast (zero top banners)
			SnackbarHost(
				hostState = snackbarHostState,
				modifier = Modifier
					.align(Alignment.BottomCenter)
					.padding(horizontal = 20.dp, vertical = 16.dp),
			) { data ->
				Snackbar(
					snackbarData = data,
					shape = RoundedCornerShape(50),
					containerColor = scheme.surfaceContainerHigh,
					contentColor = scheme.onSurface,
				)
			}
		}
	}
}

@Composable
private fun PrimaryMangaCard(
	manga: Manga,
	sourceTitle: String,
	imageLoader: ImageLoader,
) {
	val context = LocalContext.current
	val scheme = MaterialTheme.colorScheme
	Surface(
		shape = RoundedCornerShape(18.dp),
		color = scheme.surfaceContainerLow,
		border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.35f)),
		modifier = Modifier.fillMaxWidth(),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			AsyncImage(
				model = remember(manga.coverUrl, manga.source) {
					ImageRequest.Builder(context)
						.data(manga.coverUrl)
						.mangaSourceExtra(manga.source)
						.crossfade(true)
						.build()
				},
				imageLoader = imageLoader,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier
					.size(width = 44.dp, height = 62.dp)
					.clip(RoundedCornerShape(10.dp))
					.background(scheme.surfaceVariant),
			)
			Spacer(Modifier.width(12.dp))
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = stringResource(R.string.merge_primary_source_label),
					style = MaterialTheme.typography.labelSmall,
					fontWeight = FontWeight.SemiBold,
					color = scheme.primary,
				)
				Spacer(Modifier.height(2.dp))
				Text(
					text = manga.title,
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.SemiBold,
					color = scheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				Spacer(Modifier.height(4.dp))
				SourcePillBadge(text = sourceTitle, accent = scheme.primary)
			}
		}
	}
}

@Composable
private fun LinkedMergedSourceRow(
	entity: MergedMangaEntity,
	priorityRank: Int,
	canMoveUp: Boolean,
	canMoveDown: Boolean,
	sourceTitle: String,
	imageLoader: ImageLoader,
	onToggleEnabled: (Boolean) -> Unit,
	onMoveUp: () -> Unit,
	onMoveDown: () -> Unit,
	onUnlink: () -> Unit,
) {
	val context = LocalContext.current
	val scheme = MaterialTheme.colorScheme
	val resolvedSource = remember(entity.mergedSourceName) { ResolveMangaSource(entity.mergedSourceName) }

	Surface(
		shape = RoundedCornerShape(16.dp),
		color = scheme.surfaceContainerLow,
		border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.35f)),
		modifier = Modifier.fillMaxWidth(),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 12.dp, vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			AsyncImage(
				model = remember(entity.mergedCoverUrl, entity.mergedSourceName) {
					ImageRequest.Builder(context)
						.data(entity.mergedCoverUrl.ifBlank { null })
						.mangaSourceExtra(resolvedSource)
						.crossfade(true)
						.build()
				},
				imageLoader = imageLoader,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier
					.size(width = 42.dp, height = 58.dp)
					.clip(RoundedCornerShape(9.dp))
					.background(scheme.surfaceVariant),
			)
			Spacer(Modifier.width(10.dp))
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = entity.mergedTitle,
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.SemiBold,
					color = if (entity.enabled) scheme.onSurface else scheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				Spacer(Modifier.height(4.dp))
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp),
				) {
					SourcePillBadge(text = sourceTitle, accent = scheme.primary)
					Text(
						text = "#$priorityRank",
						style = MaterialTheme.typography.labelSmall,
						color = scheme.onSurfaceVariant,
					)
				}
			}

			// Priority reorder Up / Down
			Column(horizontalAlignment = Alignment.CenterHorizontally) {
				Text(
					text = "▲",
					fontSize = 11.sp,
					color = if (canMoveUp) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.25f),
					modifier = Modifier
						.clip(CircleShape)
						.clickable(enabled = canMoveUp, onClick = onMoveUp)
						.padding(horizontal = 6.dp, vertical = 2.dp),
				)
				Text(
					text = "▼",
					fontSize = 11.sp,
					color = if (canMoveDown) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.25f),
					modifier = Modifier
						.clip(CircleShape)
						.clickable(enabled = canMoveDown, onClick = onMoveDown)
						.padding(horizontal = 6.dp, vertical = 2.dp),
				)
			}

			Spacer(Modifier.width(4.dp))
			Switch(
				checked = entity.enabled,
				onCheckedChange = onToggleEnabled,
				colors = SwitchDefaults.colors(
					checkedThumbColor = Color.Black,
					checkedTrackColor = scheme.primary,
				),
			)
			IconButton(onClick = onUnlink) {
				Icon(
					painter = painterResource(R.drawable.ic_delete),
					contentDescription = stringResource(R.string.merge_unlink_button),
					tint = scheme.error.copy(alpha = 0.85f),
					modifier = Modifier.size(20.dp),
				)
			}
		}
	}
}

@Composable
private fun SearchControlsSection(
	query: String,
	altTitles: Set<String>,
	availableSources: List<MangaSource>,
	selectedSourceName: String?,
	isSearching: Boolean,
	onQueryChange: (String) -> Unit,
	onSubmitSearch: (String) -> Unit,
	onSelectSource: (String?) -> Unit,
) {
	val context = LocalContext.current
	val scheme = MaterialTheme.colorScheme

	Column(modifier = Modifier.fillMaxWidth()) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = stringResource(R.string.merge_search_sources_header),
				style = MaterialTheme.typography.labelLarge,
				fontWeight = FontWeight.SemiBold,
				color = scheme.primary,
				modifier = Modifier
					.weight(1f)
					.padding(start = 4.dp),
			)
			if (isSearching) {
				CircularProgressIndicator(
					modifier = Modifier.size(16.dp),
					strokeWidth = 2.dp,
					color = scheme.primary,
				)
			}
		}

		Spacer(Modifier.height(8.dp))

		OutlinedTextField(
			value = query,
			onValueChange = onQueryChange,
			singleLine = true,
			placeholder = {
				Text(
					text = stringResource(R.string.merge_search_hint),
					style = MaterialTheme.typography.bodyMedium,
				)
			},
			trailingIcon = {
				IconButton(onClick = { onSubmitSearch(query) }) {
					Icon(
						painter = painterResource(R.drawable.ic_search),
						contentDescription = stringResource(R.string.search),
						tint = scheme.primary,
					)
				}
			},
			keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
			keyboardActions = KeyboardActions(onSearch = { onSubmitSearch(query) }),
			shape = RoundedCornerShape(16.dp),
			colors = OutlinedTextFieldDefaults.colors(
				focusedContainerColor = scheme.surfaceContainerLow,
				unfocusedContainerColor = scheme.surfaceContainerLow,
				focusedBorderColor = scheme.primary,
				unfocusedBorderColor = scheme.outlineVariant.copy(alpha = 0.45f),
			),
			modifier = Modifier.fillMaxWidth(),
		)

		// Alternative title quick-query chips (like Neko's MergeSheet)
		if (altTitles.isNotEmpty()) {
			Spacer(Modifier.height(8.dp))
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.horizontalScroll(rememberScrollState()),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				altTitles.take(6).forEach { alt ->
					FilterChip(
						selected = query.trim().equals(alt.trim(), ignoreCase = true),
						onClick = { onSubmitSearch(alt) },
						label = {
							Text(
								text = alt,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
						},
						shape = RoundedCornerShape(50),
					)
				}
			}
		}

		// Source filter selector chips
		if (availableSources.isNotEmpty()) {
			Spacer(Modifier.height(6.dp))
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.horizontalScroll(rememberScrollState()),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				FilterChip(
					selected = selectedSourceName == null,
					onClick = { onSelectSource(null) },
					label = { Text(stringResource(R.string.merge_all_enabled_sources)) },
					shape = RoundedCornerShape(50),
					colors = FilterChipDefaults.filterChipColors(
						selectedContainerColor = scheme.primary.copy(alpha = 0.18f),
						selectedLabelColor = scheme.primary,
					),
				)
				availableSources.forEach { source ->
					val title = remember(source) { source.getTitle(context) }
					FilterChip(
						selected = selectedSourceName == source.name,
						onClick = { onSelectSource(source.name) },
						label = { Text(title, maxLines = 1) },
						shape = RoundedCornerShape(50),
						colors = FilterChipDefaults.filterChipColors(
							selectedContainerColor = scheme.primary.copy(alpha = 0.18f),
							selectedLabelColor = scheme.primary,
						),
					)
				}
			}
		}
	}
}

@Composable
private fun SearchCandidateRow(
	candidate: Manga,
	sourceTitle: String,
	isLinked: Boolean,
	imageLoader: ImageLoader,
	onLink: () -> Unit,
) {
	val context = LocalContext.current
	val scheme = MaterialTheme.colorScheme

	Surface(
		shape = RoundedCornerShape(16.dp),
		color = scheme.surfaceContainerLow,
		border = BorderStroke(
			1.dp,
			if (isLinked) scheme.primary.copy(alpha = 0.45f) else scheme.outlineVariant.copy(alpha = 0.30f),
		),
		modifier = Modifier.fillMaxWidth(),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(10.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			AsyncImage(
				model = remember(candidate.coverUrl, candidate.source) {
					ImageRequest.Builder(context)
						.data(candidate.coverUrl)
						.mangaSourceExtra(candidate.source)
						.crossfade(true)
						.build()
				},
				imageLoader = imageLoader,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier
					.size(width = 44.dp, height = 62.dp)
					.clip(RoundedCornerShape(9.dp))
					.background(scheme.surfaceVariant),
			)
			Spacer(Modifier.width(12.dp))
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = candidate.title,
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.SemiBold,
					color = scheme.onSurface,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
				Spacer(Modifier.height(4.dp))
				SourcePillBadge(text = sourceTitle, accent = scheme.primary)
			}
			Spacer(Modifier.width(8.dp))
			Surface(
				onClick = { if (!isLinked) onLink() },
				enabled = !isLinked,
				shape = RoundedCornerShape(50),
				color = if (isLinked) scheme.primary.copy(alpha = 0.14f) else scheme.primary,
			) {
				Row(
					modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Icon(
						painter = painterResource(if (isLinked) R.drawable.ic_check else R.drawable.ic_link),
						contentDescription = null,
						tint = if (isLinked) scheme.primary else Color.Black,
						modifier = Modifier.size(15.dp),
					)
					Spacer(Modifier.width(5.dp))
					Text(
						text = stringResource(
							if (isLinked) R.string.merge_linked_button else R.string.merge_link_button,
						),
						style = MaterialTheme.typography.labelMedium,
						fontWeight = FontWeight.Bold,
						color = if (isLinked) scheme.primary else Color.Black,
					)
				}
			}
		}
	}
}

@Composable
private fun SourcePillBadge(
	text: String,
	accent: Color,
) {
	Surface(
		shape = RoundedCornerShape(6.dp),
		color = accent.copy(alpha = 0.15f),
	) {
		Text(
			text = text,
			style = MaterialTheme.typography.labelSmall,
			fontWeight = FontWeight.Medium,
			color = accent,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
		)
	}
}
