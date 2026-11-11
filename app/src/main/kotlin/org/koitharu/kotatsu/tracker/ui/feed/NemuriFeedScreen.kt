package org.koitharu.kotatsu.tracker.ui.feed

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedChapterStreamRow
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedDiscoveryCardItem
import org.koitharu.kotatsu.tracker.ui.feed.model.ForYouUiState
import org.koitharu.kotatsu.tracker.ui.feed.model.GalleryUiState
import org.koitharu.kotatsu.tracker.ui.feed.model.HubUiState
import org.koitharu.kotatsu.tracker.ui.feed.model.NemuriFeedFilter
import org.koitharu.kotatsu.tracker.ui.feed.model.NemuriFeedMode
import org.koitharu.kotatsu.tracker.ui.feed.model.NemuriHubSection
import org.koitharu.kotatsu.tracker.ui.feed.model.UpdatesStreamUiState

private val NemuriCyanBadgeBg = Color(0xFF5CE1E6)
private val NemuriObsidianBadgeText = Color(0xFF090C10)
private val CoverShape = RoundedCornerShape(14.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NemuriFeedScreen(
	viewModel: FeedViewModel,
	imageLoader: ImageLoader,
	isBottomTab: Boolean,
	onOpenSettings: () -> Unit,
	onOpenDetails: (Manga) -> Unit,
	onOpenChapter: (Manga, Long?) -> Unit,
	onDownloadManga: (Manga) -> Unit,
	onLongPressManga: (Manga) -> Unit,
	onOpenExtensions: () -> Unit,
) {
	val cs = MaterialTheme.colorScheme
	val nestedScroll = rememberNestedScrollInteropConnection()
	val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()
	val bottomContentPadding = systemBarsPadding.calculateBottomPadding() + if (isBottomTab) 88.dp else 24.dp

	val selectedMode by viewModel.selectedMode.collectAsState()
	val selectedFilter by viewModel.selectedFilter.collectAsState()
	val activeHubSection by viewModel.activeHubSection.collectAsState()
	val isTrackerRunning by viewModel.isRunning.collectAsState()
	val updatesState by viewModel.updatesStreamState.collectAsState()
	val hubState by viewModel.hubState.collectAsState()
	val galleryState by viewModel.galleryState.collectAsState()
	val forYouState by viewModel.forYouState.collectAsState()

	BackHandler(enabled = selectedMode == NemuriFeedMode.GALLERY && activeHubSection != null) {
		viewModel.closeHubSectionToHub()
	}

	val isPullRefreshing = when (selectedMode) {
		NemuriFeedMode.UPDATES -> isTrackerRunning
		NemuriFeedMode.HUB -> isTrackerRunning || hubState.isDiscoveryLoading
		NemuriFeedMode.GALLERY -> isTrackerRunning || galleryState.isLoading
		NemuriFeedMode.FOR_YOU -> forYouState.isLoading
	}

	Column(
		modifier = Modifier
			.fillMaxSize()
			.background(cs.background)
			.nestedScroll(nestedScroll),
	) {
		// Standalone fallback header with left-locked [⚙] Settings icon when not inside MainActivity's dock
		if (!isBottomTab) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.background(cs.background)
					.padding(
						top = systemBarsPadding.calculateTopPadding() + 4.dp,
						start = 4.dp,
						end = 12.dp,
						bottom = 4.dp,
					),
				verticalAlignment = Alignment.CenterVertically,
			) {
				IconButton(onClick = onOpenSettings) {
					Icon(
						painter = painterResource(R.drawable.ic_settings),
						contentDescription = stringResource(R.string.settings),
						tint = cs.onSurface,
					)
				}
				Text(
					text = stringResource(R.string.feed),
					style = MaterialTheme.typography.titleLarge,
					fontWeight = FontWeight.SemiBold,
					color = cs.onSurface,
					modifier = Modifier.weight(1f),
				)
			}
		}

		// Obsidian-locked 4-mode segmented pill bar + reactive filter chips (zero purple tint, zero hard border)
		NemuriFeedTopHeader(
			selectedMode = selectedMode,
			selectedFilter = selectedFilter,
			activeHubSection = if (selectedMode == NemuriFeedMode.GALLERY) activeHubSection else null,
			onSelectMode = viewModel::setMode,
			onSelectFilter = viewModel::setFilter,
			onBackToHub = viewModel::closeHubSectionToHub,
		)

		PullToRefreshBox(
			isRefreshing = isPullRefreshing,
			onRefresh = viewModel::refreshActiveMode,
			modifier = Modifier
				.fillMaxSize()
				.background(cs.background),
		) {
			when (selectedMode) {
				NemuriFeedMode.UPDATES -> NemuriUpdatesModeContent(
					state = updatesState,
					activeFilter = selectedFilter,
					imageLoader = imageLoader,
					bottomPadding = bottomContentPadding,
					onOpenDetails = onOpenDetails,
					onOpenChapter = onOpenChapter,
					onMarkMangaRead = viewModel::markMangaUpdatesRead,
					onDownloadManga = onDownloadManga,
					onLongPressManga = onLongPressManga,
					onCheckUpdates = viewModel::update,
					onLoadMore = viewModel::requestMoreUpdates,
				)

				NemuriFeedMode.HUB -> NemuriHubModeContent(
					state = hubState,
					activeFilter = selectedFilter,
					imageLoader = imageLoader,
					bottomPadding = bottomContentPadding,
					onExpandSection = viewModel::openHubSection,
					onOpenDetails = onOpenDetails,
					onOpenChapter = onOpenChapter,
					onLongPressManga = onLongPressManga,
					onOpenExtensions = onOpenExtensions,
					onCheckUpdates = viewModel::update,
				)

				NemuriFeedMode.GALLERY -> NemuriGalleryModeContent(
					state = galleryState,
					activeFilter = selectedFilter,
					imageLoader = imageLoader,
					bottomPadding = bottomContentPadding,
					onOpenDetails = onOpenDetails,
					onOpenChapter = onOpenChapter,
					onLongPressManga = onLongPressManga,
					onCheckUpdates = viewModel::refreshActiveMode,
				)

				NemuriFeedMode.FOR_YOU -> NemuriForYouModeContent(
					state = forYouState,
					activeFilter = selectedFilter,
					imageLoader = imageLoader,
					bottomPadding = bottomContentPadding,
					onSelectAffinityTag = viewModel::selectAffinityTag,
					onOpenDetails = onOpenDetails,
					onLongPressManga = onLongPressManga,
					onOpenExtensions = onOpenExtensions,
				)
			}
		}
	}
}

@Composable
private fun NemuriFeedTopHeader(
	selectedMode: NemuriFeedMode,
	selectedFilter: NemuriFeedFilter,
	activeHubSection: NemuriHubSection?,
	onSelectMode: (NemuriFeedMode) -> Unit,
	onSelectFilter: (NemuriFeedFilter) -> Unit,
	onBackToHub: () -> Unit,
) {
	val cs = MaterialTheme.colorScheme
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(cs.background)
			.padding(top = 4.dp, bottom = 6.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		// 4-Mode Segmented Pill Bar: ★ Updates | 📖 Hub | ⊞ Gallery | ✦ For You
		Surface(
			shape = RoundedCornerShape(16.dp),
			color = cs.surfaceContainer,
			border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.45f)),
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp),
		) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(4.dp),
				horizontalArrangement = Arrangement.spacedBy(4.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				NemuriFeedMode.entries.forEach { mode ->
					val selected = mode == selectedMode
					Box(
						modifier = Modifier
							.weight(1f)
							.clip(RoundedCornerShape(12.dp))
							.background(if (selected) cs.primary else Color.Transparent)
							.clickable { onSelectMode(mode) }
							.padding(vertical = 8.dp, horizontal = 4.dp),
						contentAlignment = Alignment.Center,
					) {
						Text(
							text = stringResource(mode.labelRes),
							style = MaterialTheme.typography.labelMedium,
							fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
							fontSize = 12.sp,
							color = if (selected) cs.onPrimary else cs.onSurfaceVariant,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					}
				}
			}
		}

		// Reactive Filter Chips: All | My Follows | Unread Only | Downloaded
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.horizontalScroll(rememberScrollState())
				.padding(horizontal = 14.dp),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (activeHubSection != null) {
				Surface(
					shape = RoundedCornerShape(50),
					color = cs.secondaryContainer,
					modifier = Modifier.clickable(onClick = onBackToHub),
				) {
					Text(
						text = "${stringResource(R.string.feed_back_to_hub)} • ${stringResource(activeHubSection.titleRes)}",
						style = MaterialTheme.typography.labelSmall,
						fontWeight = FontWeight.SemiBold,
						color = cs.onSecondaryContainer,
						modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
					)
				}
			}

			NemuriFeedFilter.entries.forEach { filter ->
				val selected = filter == selectedFilter
				FilterChip(
					selected = selected,
					onClick = { onSelectFilter(filter) },
					label = {
						Text(
							text = stringResource(filter.labelRes),
							style = MaterialTheme.typography.labelSmall,
							fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
						)
					},
					shape = RoundedCornerShape(50),
					colors = FilterChipDefaults.filterChipColors(
						containerColor = cs.surfaceContainerLow,
						labelColor = cs.onSurfaceVariant,
						selectedContainerColor = cs.primary.copy(alpha = 0.2f),
						selectedLabelColor = cs.primary,
					),
					border = FilterChipDefaults.filterChipBorder(
						enabled = true,
						selected = selected,
						borderColor = cs.outlineVariant.copy(alpha = 0.5f),
						selectedBorderColor = cs.primary.copy(alpha = 0.65f),
					),
				)
			}
		}
	}
}

// =================================================================================================
// MODE 1: ★ UPDATES — Neko Chronological Chapter Release Stream
// =================================================================================================

@Composable
private fun NemuriUpdatesModeContent(
	state: UpdatesStreamUiState,
	activeFilter: NemuriFeedFilter,
	imageLoader: ImageLoader,
	bottomPadding: androidx.compose.ui.unit.Dp,
	onOpenDetails: (Manga) -> Unit,
	onOpenChapter: (Manga, Long?) -> Unit,
	onMarkMangaRead: (Manga) -> Unit,
	onDownloadManga: (Manga) -> Unit,
	onLongPressManga: (Manga) -> Unit,
	onCheckUpdates: () -> Unit,
	onLoadMore: () -> Unit,
) {
	val context = LocalContext.current
	val cs = MaterialTheme.colorScheme
	val listState = rememberLazyListState()

	val shouldLoadMore by remember {
		derivedStateOf {
			val layoutInfo = listState.layoutInfo
			val total = layoutInfo.totalItemsCount
			val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
			total > 0 && lastVisible >= total - 6
		}
	}
	LaunchedEffect(shouldLoadMore) {
		if (shouldLoadMore) {
			onLoadMore()
		}
	}

	if (state.isInitialLoading && state.groups.isEmpty()) {
		Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
			CircularProgressIndicator(color = cs.primary)
		}
		return
	}

	if (state.groups.isEmpty()) {
		val isFilteredEmpty = state.totalUnfilteredCount > 0 && activeFilter != NemuriFeedFilter.ALL
		NemuriObsidianEmptyState(
			title = stringResource(
				if (isFilteredEmpty) R.string.feed_empty_filtered_title else R.string.feed_empty_updates_title,
			),
			subtitle = stringResource(
				if (isFilteredEmpty) R.string.feed_empty_filtered_subtitle else R.string.feed_empty_updates_subtitle,
			),
			actionLabel = if (isFilteredEmpty) null else stringResource(R.string.feed_check_updates_action),
			onAction = if (isFilteredEmpty) null else onCheckUpdates,
		)
		return
	}

	LazyColumn(
		state = listState,
		modifier = Modifier
			.fillMaxSize()
			.background(cs.background),
		contentPadding = PaddingValues(
			start = 14.dp,
			end = 14.dp,
			top = 4.dp,
			bottom = bottomPadding,
		),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		state.groups.forEach { group ->
			item(key = "header_${group.key}", contentType = "date_header") {
				val label = remember(group.dateBucket, context) {
					group.dateBucket?.format(context) ?: context.getString(R.string.unknown)
				}
				Text(
					text = label,
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.Bold,
					color = cs.primary,
					modifier = Modifier.padding(top = 10.dp, bottom = 2.dp, start = 2.dp),
				)
			}
			items(
				items = group.rows,
				key = { it.key },
				contentType = { "chapter_row" },
			) { row ->
				NemuriUpdatesChapterRow(
					row = row,
					imageLoader = imageLoader,
					onCoverClick = { onOpenDetails(row.manga) },
					onCoverLongClick = { onLongPressManga(row.manga) },
					onRowClick = { onOpenChapter(row.manga, row.chapterId) },
					onMarkReadClick = { onMarkMangaRead(row.manga) },
					onDownloadClick = { onDownloadManga(row.manga) },
				)
			}
		}
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NemuriUpdatesChapterRow(
	row: FeedChapterStreamRow,
	imageLoader: ImageLoader,
	onCoverClick: () -> Unit,
	onCoverLongClick: () -> Unit,
	onRowClick: () -> Unit,
	onMarkReadClick: () -> Unit,
	onDownloadClick: () -> Unit,
) {
	val cs = MaterialTheme.colorScheme
	val context = LocalContext.current
	val contentAlpha = if (row.isUnread) 1f else 0.54f
	val sourceLabel = remember(row.manga.source, context) {
		row.manga.source.getTitle(context)
	}
	val relativeTime = remember(row.createdAt) {
		DateUtils.getRelativeTimeSpanString(
			row.createdAt.toEpochMilli(),
			System.currentTimeMillis(),
			DateUtils.MINUTE_IN_MILLIS,
			DateUtils.FORMAT_ABBREV_RELATIVE,
		).toString()
	}

	Surface(
		shape = RoundedCornerShape(16.dp),
		color = cs.surfaceContainerLow,
		border = BorderStroke(
			1.dp,
			if (row.isUnread) cs.outlineVariant.copy(alpha = 0.5f) else cs.outlineVariant.copy(alpha = 0.25f),
		),
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onRowClick),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 10.dp, vertical = 9.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			// Tap cover -> MangaDetails
			Box(
				modifier = Modifier
					.size(width = 48.dp, height = 68.dp)
					.clip(RoundedCornerShape(10.dp))
					.background(cs.surfaceContainerHigh)
					.alpha(contentAlpha)
					.combinedClickable(
						onClick = onCoverClick,
						onLongClick = onCoverLongClick,
					),
			) {
				AsyncImage(
					model = remember(row.manga.coverUrl, row.manga.source, context) {
						ImageRequest.Builder(context)
							.data(row.manga.coverUrl)
							.mangaSourceExtra(row.manga.source)
							.crossfade(true)
							.build()
					},
					imageLoader = imageLoader,
					contentDescription = row.manga.title,
					contentScale = ContentScale.Crop,
					modifier = Modifier.fillMaxSize(),
				)
			}

			Spacer(Modifier.width(12.dp))

			Column(
				modifier = Modifier
					.weight(1f)
					.alpha(contentAlpha),
				verticalArrangement = Arrangement.spacedBy(3.dp),
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp),
				) {
					if (row.isUnread) {
						Box(
							modifier = Modifier
								.size(7.dp)
								.clip(CircleShape)
								.background(NemuriCyanBadgeBg),
						)
					}
					Text(
						text = row.manga.title,
						style = MaterialTheme.typography.titleSmall,
						fontWeight = if (row.isUnread) FontWeight.SemiBold else FontWeight.Medium,
						color = cs.onSurface,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}

				Text(
					text = row.chapterTitle,
					style = MaterialTheme.typography.bodySmall,
					fontWeight = if (row.isUnread) FontWeight.Medium else FontWeight.Normal,
					color = if (row.isUnread) cs.onSurfaceVariant else cs.onSurfaceVariant.copy(alpha = 0.85f),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)

				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp),
				) {
					Surface(
						shape = RoundedCornerShape(6.dp),
						color = cs.surfaceContainerHighest,
					) {
						Text(
							text = sourceLabel,
							style = MaterialTheme.typography.labelSmall,
							fontSize = 10.sp,
							color = cs.onSurfaceVariant,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
						)
					}
					Text(
						text = relativeTime,
						style = MaterialTheme.typography.labelSmall,
						fontSize = 11.sp,
						color = cs.onSurfaceVariant.copy(alpha = 0.8f),
						maxLines = 1,
					)
				}
			}

			Row(verticalAlignment = Alignment.CenterVertically) {
				if (row.isUnread) {
					IconButton(
						onClick = onMarkReadClick,
						modifier = Modifier.size(36.dp),
					) {
						Icon(
							painter = painterResource(R.drawable.ic_check),
							contentDescription = stringResource(R.string.mark_as_read),
							tint = cs.primary,
							modifier = Modifier.size(18.dp),
						)
					}
				}
				IconButton(
					onClick = onDownloadClick,
					modifier = Modifier.size(36.dp),
				) {
					Icon(
						painter = painterResource(
							if (row.isDownloaded) R.drawable.ic_storage_checked else R.drawable.ic_download,
						),
						contentDescription = stringResource(R.string.download),
						tint = if (row.isDownloaded) NemuriCyanBadgeBg else cs.onSurfaceVariant,
						modifier = Modifier.size(18.dp),
					)
				}
			}
		}
	}
}

// =================================================================================================
// MODE 2: 📖 HUB — Neko Horizontal Discovery Swimlanes + Strict Anti-Clipping Cards
// =================================================================================================

@Composable
private fun NemuriHubModeContent(
	state: HubUiState,
	activeFilter: NemuriFeedFilter,
	imageLoader: ImageLoader,
	bottomPadding: androidx.compose.ui.unit.Dp,
	onExpandSection: (NemuriHubSection) -> Unit,
	onOpenDetails: (Manga) -> Unit,
	onOpenChapter: (Manga, Long?) -> Unit,
	onLongPressManga: (Manga) -> Unit,
	onOpenExtensions: () -> Unit,
	onCheckUpdates: () -> Unit,
) {
	val cs = MaterialTheme.colorScheme

	LazyColumn(
		modifier = Modifier
			.fillMaxSize()
			.background(cs.background),
		contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding),
		verticalArrangement = Arrangement.spacedBy(20.dp),
	) {
		// Swimlane 1: Feed updates →
		item(key = "swimlane_feed_updates") {
			NemuriHubSwimlaneSection(
				title = stringResource(R.string.feed_hub_updates_title),
				items = state.feedUpdates,
				isLoading = false,
				emptyMessage = if (activeFilter != NemuriFeedFilter.ALL) {
					stringResource(R.string.feed_empty_filtered_subtitle)
				} else {
					stringResource(R.string.feed_empty_updates_subtitle)
				},
				emptyActionLabel = if (activeFilter == NemuriFeedFilter.ALL) {
					stringResource(R.string.feed_check_updates_action)
				} else {
					null
				},
				onEmptyAction = onCheckUpdates,
				onHeaderClick = { onExpandSection(NemuriHubSection.FEED_UPDATES) },
				imageLoader = imageLoader,
				onCardClick = { card ->
					if (card.chapterId != null) {
						onOpenChapter(card.manga, card.chapterId)
					} else {
						onOpenDetails(card.manga)
					}
				},
				onTitleClick = { card -> onOpenDetails(card.manga) },
				onCardLongClick = { card -> onOpenDetails(card.manga) },
			)
		}

		if (!state.hasEnabledSources) {
			item(key = "hub_no_sources") {
				Box(modifier = Modifier.padding(horizontal = 14.dp)) {
					NemuriObsidianInlineCard(
						title = stringResource(R.string.feed_hub_no_sources_title),
						subtitle = stringResource(R.string.feed_hub_no_sources_subtitle),
						actionLabel = stringResource(R.string.enhance_open_extensions),
						onAction = onOpenExtensions,
					)
				}
			}
		} else {
			// Swimlane 2: Popular new titles →
			item(key = "swimlane_popular_new") {
				NemuriHubSwimlaneSection(
					title = stringResource(R.string.feed_hub_popular_new_title),
					items = state.popularNewTitles,
					isLoading = state.isDiscoveryLoading,
					emptyMessage = stringResource(R.string.feed_empty_filtered_subtitle),
					emptyActionLabel = null,
					onEmptyAction = null,
					onHeaderClick = { onExpandSection(NemuriHubSection.POPULAR_NEW) },
					imageLoader = imageLoader,
					onCardClick = { card -> onOpenDetails(card.manga) },
					onTitleClick = { card -> onOpenDetails(card.manga) },
					onCardLongClick = { card -> onLongPressManga(card.manga) },
				)
			}

			// Swimlane 3: Latest uploads →
			item(key = "swimlane_latest_uploads") {
				NemuriHubSwimlaneSection(
					title = stringResource(R.string.feed_hub_latest_uploads_title),
					items = state.latestUploads,
					isLoading = state.isDiscoveryLoading,
					emptyMessage = stringResource(R.string.feed_empty_filtered_subtitle),
					emptyActionLabel = null,
					onEmptyAction = null,
					onHeaderClick = { onExpandSection(NemuriHubSection.LATEST_UPLOADS) },
					imageLoader = imageLoader,
					onCardClick = { card -> onOpenDetails(card.manga) },
					onTitleClick = { card -> onOpenDetails(card.manga) },
					onCardLongClick = { card -> onLongPressManga(card.manga) },
				)
			}
		}
	}
}

@Composable
private fun NemuriHubSwimlaneSection(
	title: String,
	items: List<FeedDiscoveryCardItem>,
	isLoading: Boolean,
	emptyMessage: String,
	emptyActionLabel: String?,
	onEmptyAction: (() -> Unit)?,
	onHeaderClick: () -> Unit,
	imageLoader: ImageLoader,
	onCardClick: (FeedDiscoveryCardItem) -> Unit,
	onTitleClick: (FeedDiscoveryCardItem) -> Unit,
	onCardLongClick: (FeedDiscoveryCardItem) -> Unit,
) {
	val cs = MaterialTheme.colorScheme
	Column(
		modifier = Modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.clickable(onClick = onHeaderClick)
				.padding(horizontal = 14.dp, vertical = 2.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Text(
				text = "$title →",
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.Bold,
				color = cs.onSurface,
			)
			if (items.isNotEmpty()) {
				Text(
					text = items.size.toString(),
					style = MaterialTheme.typography.labelMedium,
					color = cs.primary,
				)
			}
		}

		when {
			isLoading && items.isEmpty() -> {
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.height(210.dp),
					contentAlignment = Alignment.Center,
				) {
					CircularProgressIndicator(
						color = cs.primary,
						modifier = Modifier.size(28.dp),
					)
				}
			}

			items.isEmpty() -> {
				Box(modifier = Modifier.padding(horizontal = 14.dp)) {
					NemuriObsidianInlineCard(
						title = null,
						subtitle = emptyMessage,
						actionLabel = emptyActionLabel,
						onAction = onEmptyAction,
					)
				}
			}

			else -> {
				LazyRow(
					contentPadding = PaddingValues(horizontal = 14.dp),
					horizontalArrangement = Arrangement.spacedBy(12.dp),
				) {
					items(
						items = items,
						key = { it.key },
						contentType = { "swimlane_card" },
					) { card ->
						NemuriDiscoveryCoverCard(
							item = card,
							imageLoader = imageLoader,
							onClick = { onCardClick(card) },
							onTitleClick = { onTitleClick(card) },
							onLongClick = { onCardLongClick(card) },
							modifier = Modifier.width(146.dp),
						)
					}
				}
			}
		}
	}
}

// =================================================================================================
// STRICT ANTI-CLIPPING COVER CARD (Shared by Hub Swimlanes, Gallery Grid & For You Grid)
// =================================================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NemuriDiscoveryCoverCard(
	item: FeedDiscoveryCardItem,
	imageLoader: ImageLoader,
	onClick: () -> Unit,
	onTitleClick: () -> Unit,
	onLongClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val cs = MaterialTheme.colorScheme
	val context = LocalContext.current
	val sourceTitle = remember(item.manga.source, context) {
		item.manga.source.getTitle(context)
	}
	val subtitleText = remember(item.subtitleOverride, sourceTitle) {
		if (!item.subtitleOverride.isNullOrBlank()) {
			"${item.subtitleOverride} • $sourceTitle"
		} else {
			sourceTitle
		}
	}

	// CRITICAL ANTI-CLIPPING GEOMETRY:
	// Outer Column MUST stay completely unclipped so 2-line titles & chapter/source subtitles
	// below the cover are NEVER sliced off at the corners.
	Column(modifier = modifier) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.aspectRatio(2f / 3f)
				.clip(CoverShape)
				.background(cs.surfaceContainerHigh)
				.combinedClickable(
					onClick = onClick,
					onLongClick = onLongClick,
				),
		) {
			AsyncImage(
				model = remember(item.manga.coverUrl, item.manga.source, context) {
					ImageRequest.Builder(context)
						.data(item.manga.coverUrl)
						.mangaSourceExtra(item.manga.source)
						.crossfade(true)
						.build()
				},
				imageLoader = imageLoader,
				contentDescription = item.manga.title,
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxSize(),
			)

			if (!item.chapterPill.isNullOrBlank()) {
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.height(48.dp)
						.align(Alignment.BottomCenter)
						.background(
							Brush.verticalGradient(
								colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.68f)),
							),
						),
				)
			}

			// Live "In Library" cyan/obsidian pill strictly inside the inner clipped cover Box
			if (item.inLibrary) {
				Surface(
					shape = RoundedCornerShape(50),
					color = NemuriCyanBadgeBg,
					modifier = Modifier
						.padding(8.dp)
						.align(Alignment.TopStart),
				) {
					Text(
						text = stringResource(R.string.feed_in_library_badge),
						style = MaterialTheme.typography.labelSmall,
						fontWeight = FontWeight.Bold,
						fontSize = 10.sp,
						color = NemuriObsidianBadgeText,
						maxLines = 1,
						modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
					)
				}
			}

			if (!item.chapterPill.isNullOrBlank()) {
				Surface(
					shape = RoundedCornerShape(8.dp),
					color = NemuriObsidianBadgeText.copy(alpha = 0.86f),
					border = BorderStroke(1.dp, NemuriCyanBadgeBg.copy(alpha = 0.45f)),
					modifier = Modifier
						.padding(8.dp)
						.align(Alignment.BottomEnd),
				) {
					Text(
						text = item.chapterPill,
						style = MaterialTheme.typography.labelSmall,
						fontWeight = FontWeight.SemiBold,
						fontSize = 10.sp,
						color = Color.White,
						maxLines = 1,
						modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
					)
				}
			}
		}

		// Unclipped outer text area below the 2:3 cover Box
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.clickable(onClick = onTitleClick)
				.padding(start = 2.dp, end = 2.dp, top = 6.dp),
			verticalArrangement = Arrangement.spacedBy(2.dp),
		) {
			Text(
				text = item.manga.title,
				style = MaterialTheme.typography.bodySmall,
				fontWeight = FontWeight.SemiBold,
				color = cs.onSurface,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = subtitleText,
				style = MaterialTheme.typography.labelSmall,
				fontSize = 11.sp,
				color = cs.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

// =================================================================================================
// MODE 3: ⊞ GALLERY — 3-Column High-Density Cover Grid
// =================================================================================================

@Composable
private fun NemuriGalleryModeContent(
	state: GalleryUiState,
	activeFilter: NemuriFeedFilter,
	imageLoader: ImageLoader,
	bottomPadding: androidx.compose.ui.unit.Dp,
	onOpenDetails: (Manga) -> Unit,
	onOpenChapter: (Manga, Long?) -> Unit,
	onLongPressManga: (Manga) -> Unit,
	onCheckUpdates: () -> Unit,
) {
	val cs = MaterialTheme.colorScheme

	if (state.isLoading && state.items.isEmpty()) {
		Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
			CircularProgressIndicator(color = cs.primary)
		}
		return
	}

	if (state.items.isEmpty()) {
		val isFilteredEmpty = state.totalUnfilteredCount > 0 && activeFilter != NemuriFeedFilter.ALL
		NemuriObsidianEmptyState(
			title = stringResource(
				if (isFilteredEmpty) R.string.feed_empty_filtered_title else R.string.feed_empty_updates_title,
			),
			subtitle = stringResource(
				if (isFilteredEmpty) R.string.feed_empty_filtered_subtitle else R.string.feed_empty_updates_subtitle,
			),
			actionLabel = if (isFilteredEmpty) null else stringResource(R.string.feed_check_updates_action),
			onAction = if (isFilteredEmpty) null else onCheckUpdates,
		)
		return
	}

	LazyVerticalGrid(
		columns = GridCells.Fixed(3),
		modifier = Modifier
			.fillMaxSize()
			.background(cs.background),
		contentPadding = PaddingValues(
			start = 14.dp,
			end = 14.dp,
			top = 6.dp,
			bottom = bottomPadding,
		),
		horizontalArrangement = Arrangement.spacedBy(10.dp),
		verticalArrangement = Arrangement.spacedBy(14.dp),
	) {
		items(
			items = state.items,
			key = { it.key },
			contentType = { "gallery_card" },
		) { card ->
			val isUpdateChapterCard = card.chapterId != null
			NemuriDiscoveryCoverCard(
				item = card,
				imageLoader = imageLoader,
				onClick = {
					if (isUpdateChapterCard) {
						onOpenChapter(card.manga, card.chapterId)
					} else {
						onOpenDetails(card.manga)
					}
				},
				onTitleClick = { onOpenDetails(card.manga) },
				onLongClick = {
					if (isUpdateChapterCard) {
						onOpenDetails(card.manga)
					} else {
						onLongPressManga(card.manga)
					}
				},
				modifier = Modifier.fillMaxWidth(),
			)
		}
	}
}

// =================================================================================================
// MODE 4: ✦ FOR YOU — Real Local Tag-Affinity Discovery
// =================================================================================================

@Composable
private fun NemuriForYouModeContent(
	state: ForYouUiState,
	activeFilter: NemuriFeedFilter,
	imageLoader: ImageLoader,
	bottomPadding: androidx.compose.ui.unit.Dp,
	onSelectAffinityTag: (String?) -> Unit,
	onOpenDetails: (Manga) -> Unit,
	onLongPressManga: (Manga) -> Unit,
	onOpenExtensions: () -> Unit,
) {
	val cs = MaterialTheme.colorScheme

	if (state.isLoading && state.items.isEmpty()) {
		Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
			CircularProgressIndicator(color = cs.primary)
		}
		return
	}

	if (state.isLibraryAndHistoryEmpty) {
		NemuriObsidianEmptyState(
			title = stringResource(R.string.feed_for_you_empty_library_title),
			subtitle = stringResource(R.string.feed_for_you_empty_library_subtitle),
			actionLabel = null,
			onAction = null,
		)
		return
	}

	if (state.items.isEmpty()) {
		NemuriObsidianEmptyState(
			title = stringResource(
				if (activeFilter != NemuriFeedFilter.ALL) {
					R.string.feed_empty_filtered_title
				} else {
					R.string.feed_for_you_no_matches_title
				},
			),
			subtitle = stringResource(
				if (activeFilter != NemuriFeedFilter.ALL) {
					R.string.feed_empty_filtered_subtitle
				} else {
					R.string.feed_for_you_no_matches_subtitle
				},
			),
			actionLabel = if (activeFilter == NemuriFeedFilter.ALL) {
				stringResource(R.string.enhance_open_extensions)
			} else {
				null
			},
			onAction = if (activeFilter == NemuriFeedFilter.ALL) onOpenExtensions else null,
		)
		return
	}

	LazyVerticalGrid(
		columns = GridCells.Fixed(3),
		modifier = Modifier
			.fillMaxSize()
			.background(cs.background),
		contentPadding = PaddingValues(
			start = 14.dp,
			end = 14.dp,
			top = 4.dp,
			bottom = bottomPadding,
		),
		horizontalArrangement = Arrangement.spacedBy(10.dp),
		verticalArrangement = Arrangement.spacedBy(14.dp),
	) {
		if (state.affinityTags.isNotEmpty()) {
			item(
				key = "affinity_tags_header",
				span = { GridItemSpan(maxLineSpan) },
			) {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.padding(bottom = 4.dp),
					verticalArrangement = Arrangement.spacedBy(6.dp),
				) {
					Text(
						text = stringResource(R.string.feed_for_you_affinity_label),
						style = MaterialTheme.typography.labelMedium,
						color = cs.onSurfaceVariant,
					)
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.horizontalScroll(rememberScrollState()),
						horizontalArrangement = Arrangement.spacedBy(6.dp),
					) {
						state.affinityTags.forEach { tagTitle ->
							val selected = state.selectedTag.equals(tagTitle, ignoreCase = true)
							FilterChip(
								selected = selected,
								onClick = { onSelectAffinityTag(tagTitle) },
								label = {
									Text(
										text = tagTitle,
										style = MaterialTheme.typography.labelSmall,
									)
								},
								shape = RoundedCornerShape(50),
								colors = FilterChipDefaults.filterChipColors(
									containerColor = cs.surfaceContainerLow,
									labelColor = cs.onSurfaceVariant,
									selectedContainerColor = NemuriCyanBadgeBg.copy(alpha = 0.22f),
									selectedLabelColor = NemuriCyanBadgeBg,
								),
							)
						}
					}
				}
			}
		}

		items(
			items = state.items,
			key = { it.key },
			contentType = { "for_you_card" },
		) { card ->
			NemuriDiscoveryCoverCard(
				item = card,
				imageLoader = imageLoader,
				onClick = { onOpenDetails(card.manga) },
				onTitleClick = { onOpenDetails(card.manga) },
				onLongClick = { onLongPressManga(card.manga) },
				modifier = Modifier.fillMaxWidth(),
			)
		}
	}
}

// =================================================================================================
// OBSIDIAN EMPTY STATES (Zero Mock Data)
// =================================================================================================

@Composable
private fun NemuriObsidianEmptyState(
	title: String,
	subtitle: String,
	actionLabel: String?,
	onAction: (() -> Unit)?,
) {
	val cs = MaterialTheme.colorScheme
	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(cs.background)
			.padding(horizontal = 32.dp),
		contentAlignment = Alignment.Center,
	) {
		Column(
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Surface(
				shape = CircleShape,
				color = cs.surfaceContainer,
				border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.45f)),
				modifier = Modifier.size(64.dp),
			) {
				Box(contentAlignment = Alignment.Center) {
					Icon(
						painter = painterResource(R.drawable.ic_empty_feed),
						contentDescription = null,
						tint = cs.primary,
						modifier = Modifier.size(30.dp),
					)
				}
			}
			Text(
				text = title,
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.Bold,
				color = cs.onSurface,
				textAlign = TextAlign.Center,
			)
			Text(
				text = subtitle,
				style = MaterialTheme.typography.bodyMedium,
				color = cs.onSurfaceVariant,
				textAlign = TextAlign.Center,
			)
			if (actionLabel != null && onAction != null) {
				Spacer(Modifier.height(4.dp))
				Button(
					onClick = onAction,
					shape = RoundedCornerShape(50),
					colors = ButtonDefaults.buttonColors(
						containerColor = cs.primary,
						contentColor = cs.onPrimary,
					),
				) {
					Text(
						text = actionLabel,
						fontWeight = FontWeight.SemiBold,
					)
				}
			}
		}
	}
}

@Composable
private fun NemuriObsidianInlineCard(
	title: String?,
	subtitle: String,
	actionLabel: String?,
	onAction: (() -> Unit)?,
) {
	val cs = MaterialTheme.colorScheme
	Surface(
		shape = RoundedCornerShape(18.dp),
		color = cs.surfaceContainerLow,
		border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.45f)),
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(
			modifier = Modifier.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			if (!title.isNullOrBlank()) {
				Text(
					text = title,
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.SemiBold,
					color = cs.onSurface,
				)
			}
			Text(
				text = subtitle,
				style = MaterialTheme.typography.bodySmall,
				color = cs.onSurfaceVariant,
			)
			if (actionLabel != null && onAction != null) {
				Button(
					onClick = onAction,
					shape = RoundedCornerShape(50),
					colors = ButtonDefaults.buttonColors(
						containerColor = cs.primary,
						contentColor = cs.onPrimary,
					),
				) {
					Text(text = actionLabel)
				}
			}
		}
	}
}
