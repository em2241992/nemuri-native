package org.koitharu.kotatsu.enhance.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.DedupeWinnerRule
import org.koitharu.kotatsu.core.prefs.ReaderUpscalePreset
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.enhance.domain.DuplicatePairItem
import org.koitharu.kotatsu.enhance.domain.EnhanceTab
import org.koitharu.kotatsu.enhance.domain.LibraryEntrySnapshot
import org.koitharu.kotatsu.enhance.domain.RepairIssueKind
import org.koitharu.kotatsu.enhance.domain.RepairItem
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.reader.ui.upscale.UpscalePresetGridSelector
import org.koitharu.kotatsu.reader.ui.upscale.UpscaleSplitComparisonFrame
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import javax.inject.Inject
import kotlin.math.roundToInt

private val ObsidianCanvas = Color(0xFF090C10)
private val ObsidianCardSurface = Color(0xFF12161F)
private val ObsidianInnerSurface = Color(0xFF0D1118)
private val NemuriCyan = Color(0xFF5CE1E6)
private val NemuriDarkInk = Color(0xFF061318)
private val NemuriMuted = Color(0xFF94A3B8)
private val HealthyGreen = Color(0xFF10B981)
private val WarningAmber = Color(0xFFF59E0B)

@AndroidEntryPoint
class EnhanceFragment : Fragment() {

	@Inject
	lateinit var coil: ImageLoader

	private val viewModel by viewModels<EnhanceViewModel>()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		val isBottomTab = arguments?.getBoolean(AppRouter.KEY_IS_BOTTOMTAB, true) != false
		return ComposeView(requireContext()).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				DropSauceTheme {
					EnhanceHubScreen(
						viewModel = viewModel,
						imageLoader = coil,
						isBottomTab = isBottomTab,
						onOpenSettings = { router.openSettings() },
						onOpenDetails = { manga -> router.openDetails(manga) },
						onRequestMigrateDialog = { pair -> showNativeMigrationDialog(pair) },
						onOpenAlternatives = { manga -> router.openAlternatives(manga) },
						onOpenMergeSheet = { manga -> router.showMergeSourcesSheet(manga) },
						onOpenBulkBrokenMigration = {
							startActivity(
								Intent(requireContext(), SettingsActivity::class.java).apply {
									action = SettingsActivity.ACTION_BROKEN_SOURCES
								},
							)
						},
						onOpenReaderSettings = { router.openReaderSettings() },
					)
				}
			}
		}
	}

	private fun showNativeMigrationDialog(pair: DuplicatePairItem) {
		val ctx = context ?: return
		buildAlertDialog(ctx, isCentered = true) {
			setIcon(R.drawable.ic_swap)
			setTitle(R.string.manga_migration)
			setMessage(
				getString(
					R.string.migrate_confirmation,
					pair.secondary.manga.title,
					pair.secondary.sourceTitle,
					pair.primary.manga.title,
					pair.primary.sourceTitle,
				),
			)
			setNeutralButton(R.string.enhance_find_other_sources) { _, _ ->
				router.openAlternatives(pair.secondary.manga)
			}
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.migrate) { _, _ ->
				viewModel.migratePair(pair)
			}
		}.show()
	}
}

@Composable
private fun EnhanceHubScreen(
	viewModel: EnhanceViewModel,
	imageLoader: ImageLoader,
	isBottomTab: Boolean,
	onOpenSettings: () -> Unit,
	onOpenDetails: (Manga) -> Unit,
	onRequestMigrateDialog: (DuplicatePairItem) -> Unit,
	onOpenAlternatives: (Manga) -> Unit,
	onOpenMergeSheet: (Manga) -> Unit,
	onOpenBulkBrokenMigration: () -> Unit,
	onOpenReaderSettings: () -> Unit,
) {
	val state by viewModel.state.collectAsState()
	val nestedScroll = rememberNestedScrollInteropConnection()
	val bottomInset = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
	val topStatusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

	var showPrioritySelector by remember { mutableStateOf(false) }
	var activeCapsuleMessage by remember { mutableStateOf<String?>(null) }

	LaunchedEffect(viewModel) {
		viewModel.capsuleToastEvent.collectLatest { message ->
			activeCapsuleMessage = message
			delay(2_600L)
			if (activeCapsuleMessage == message) {
				activeCapsuleMessage = null
			}
		}
	}

	if (showPrioritySelector) {
		MihonSourcePrioritySelectorDialog(
			candidates = state.priorityCandidates,
			initialSelectedKeys = state.selectedPriorityKeys,
			imageLoader = imageLoader,
			onConfirm = { orderedKeys ->
				viewModel.saveSourcePriorityOrder(orderedKeys)
				showPrioritySelector = false
			},
			onDismiss = { showPrioritySelector = false },
		)
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(ObsidianCanvas),
	) {
		Column(
			modifier = Modifier
				.fillMaxSize()
				.background(ObsidianCanvas),
		) {
			// Standalone header when opened outside MainActivity's bottom dock
			if (!isBottomTab) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.background(ObsidianCanvas)
						.padding(top = topStatusInset, start = 8.dp, end = 16.dp, bottom = 4.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					IconButton(onClick = onOpenSettings) {
						Icon(
							painter = painterResource(R.drawable.ic_settings),
							contentDescription = stringResource(R.string.settings),
							tint = Color.White,
						)
					}
					Spacer(Modifier.width(4.dp))
					Text(
						text = stringResource(R.string.enhance),
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.Bold,
						color = Color.White,
					)
				}
			}

			// 3-Mode Nemuri Obsidian Segmented Pill Bar (Zero purple tint, zero hard bottom border)
			EnhanceSegmentedPillBar(
				selectedTab = state.selectedTab,
				duplicatesCount = state.duplicatePairs.size,
				repairCount = state.repairItems.size,
				onSelectTab = viewModel::selectTab,
			)

			when (state.selectedTab) {
				EnhanceTab.DUPLICATES -> DuplicatesTabContent(
					state = state,
					imageLoader = imageLoader,
					nestedScroll = nestedScroll,
					bottomPadding = bottomInset + 96.dp,
					onToggleCheckSwap = viewModel::setCheckAndSwapOnAdd,
					onSelectWinnerRule = viewModel::setWinnerRule,
					onMergeAll = viewModel::mergeAllPairs,
					onOpenPrioritySelector = { showPrioritySelector = true },
					onResetIgnored = viewModel::resetIgnoredPairs,
					onSelectPrimarySide = viewModel::setManualPrimary,
					onIgnorePair = viewModel::ignorePair,
					onMergePair = viewModel::mergeSinglePair,
					onMigratePair = onRequestMigrateDialog,
					onOpenDetails = onOpenDetails,
				)

				EnhanceTab.REPAIR -> RepairTabContent(
					state = state,
					imageLoader = imageLoader,
					nestedScroll = nestedScroll,
					bottomPadding = bottomInset + 96.dp,
					onScanHealth = viewModel::scanLibraryHealth,
					onOpenBulkBrokenMigration = onOpenBulkBrokenMigration,
					onRefreshItem = viewModel::refreshRepairItem,
					onMigrateItem = onOpenAlternatives,
					onLinkBackupSource = onOpenMergeSheet,
					onOpenDetails = onOpenDetails,
				)

				EnhanceTab.UPSCALER -> UpscalerTabContent(
					state = state,
					nestedScroll = nestedScroll,
					bottomPadding = bottomInset + 96.dp,
					onSelectPreset = viewModel::selectUpscalePreset,
					onToggleActive = viewModel::setUpscaleActive,
					onToggle32Bit = viewModel::set32BitColor,
					onSelectSample = viewModel::selectUpscaleSample,
					onOpenReaderSettings = onOpenReaderSettings,
				)
			}
		}

		// Floating Bottom Capsule Toast (strictly bottom-anchored — never a top banner)
		AnimatedVisibility(
			visible = activeCapsuleMessage != null,
			enter = fadeIn() + slideInVertically { it / 2 },
			exit = fadeOut() + slideOutVertically { it / 2 },
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.padding(
					start = 24.dp,
					end = 24.dp,
					bottom = bottomInset + if (isBottomTab) 84.dp else 24.dp,
				),
		) {
			Surface(
				shape = RoundedCornerShape(50),
				color = Color(0xF0181E2B),
				border = BorderStroke(1.dp, NemuriCyan.copy(alpha = 0.45f)),
				shadowElevation = 10.dp,
			) {
				Row(
					modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Icon(
						painter = painterResource(R.drawable.ic_sparkles),
						contentDescription = null,
						tint = NemuriCyan,
						modifier = Modifier.size(16.dp),
					)
					Text(
						text = activeCapsuleMessage.orEmpty(),
						style = MaterialTheme.typography.labelLarge,
						fontWeight = FontWeight.SemiBold,
						color = Color.White,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
		}
	}
}

@Composable
private fun EnhanceSegmentedPillBar(
	selectedTab: EnhanceTab,
	duplicatesCount: Int,
	repairCount: Int,
	onSelectTab: (EnhanceTab) -> Unit,
) {
	Surface(
		shape = RoundedCornerShape(18.dp),
		color = ObsidianCardSurface,
		border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 8.dp),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(4.dp),
			horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			EnhanceTab.entries.forEach { tab ->
				val selected = tab == selectedTab
				val badgeCount = when (tab) {
					EnhanceTab.DUPLICATES -> duplicatesCount
					EnhanceTab.REPAIR -> repairCount
					EnhanceTab.UPSCALER -> 0
				}
				Surface(
					shape = RoundedCornerShape(14.dp),
					color = if (selected) NemuriCyan else Color.Transparent,
					modifier = Modifier
						.weight(1f)
						.clip(RoundedCornerShape(14.dp))
						.clickable { onSelectTab(tab) },
				) {
					Row(
						modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
						horizontalArrangement = Arrangement.Center,
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							text = stringResource(tab.labelRes),
							style = MaterialTheme.typography.labelLarge,
							fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
							color = if (selected) NemuriDarkInk else NemuriMuted,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						if (badgeCount > 0) {
							Spacer(Modifier.width(6.dp))
							Surface(
								shape = CircleShape,
								color = if (selected) {
									NemuriDarkInk.copy(alpha = 0.18f)
								} else {
									NemuriCyan.copy(alpha = 0.18f)
								},
							) {
								Text(
									text = badgeCount.toString(),
									style = MaterialTheme.typography.labelSmall,
									fontSize = 10.sp,
									fontWeight = FontWeight.Bold,
									color = if (selected) NemuriDarkInk else NemuriCyan,
									modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
								)
							}
						}
					}
				}
			}
		}
	}
}

@Composable
private fun DuplicatesTabContent(
	state: EnhanceUiState,
	imageLoader: ImageLoader,
	nestedScroll: androidx.compose.ui.input.nestedscroll.NestedScrollConnection,
	bottomPadding: androidx.compose.ui.unit.Dp,
	onToggleCheckSwap: (Boolean) -> Unit,
	onSelectWinnerRule: (DedupeWinnerRule) -> Unit,
	onMergeAll: () -> Unit,
	onOpenPrioritySelector: () -> Unit,
	onResetIgnored: () -> Unit,
	onSelectPrimarySide: (String, Boolean) -> Unit,
	onIgnorePair: (String) -> Unit,
	onMergePair: (DuplicatePairItem) -> Unit,
	onMigratePair: (DuplicatePairItem) -> Unit,
	onOpenDetails: (Manga) -> Unit,
) {
	LazyColumn(
		modifier = Modifier
			.fillMaxSize()
			.nestedScroll(nestedScroll),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 6.dp,
			bottom = bottomPadding,
		),
		verticalArrangement = Arrangement.spacedBy(14.dp),
	) {
		// Top Control Card (#12161F Obsidian Surface, #5CE1E6 Cyan Accents)
		item(key = "dedupe_control_card") {
			Surface(
				shape = RoundedCornerShape(22.dp),
				color = ObsidianCardSurface,
				border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
				modifier = Modifier.fillMaxWidth(),
			) {
				Column(
					modifier = Modifier.padding(18.dp),
					verticalArrangement = Arrangement.spacedBy(14.dp),
				) {
					// Check & Swap on add (ON / OFF)
					Row(
						modifier = Modifier.fillMaxWidth(),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.SpaceBetween,
					) {
						Column(
							modifier = Modifier
								.weight(1f)
								.padding(end = 12.dp),
						) {
							Text(
								text = stringResource(R.string.enhance_check_swap_on_add),
								style = MaterialTheme.typography.titleSmall,
								fontWeight = FontWeight.Bold,
								color = Color.White,
							)
							Spacer(Modifier.height(2.dp))
							Text(
								text = stringResource(R.string.enhance_check_swap_on_add_summary),
								style = MaterialTheme.typography.bodySmall,
								color = NemuriMuted,
							)
						}
						Switch(
							checked = state.checkAndSwapOnAdd,
							onCheckedChange = onToggleCheckSwap,
							colors = SwitchDefaults.colors(
								checkedThumbColor = NemuriDarkInk,
								checkedTrackColor = NemuriCyan,
							),
						)
					}

					HorizontalDivider(color = Color.White.copy(alpha = 0.07f))

					// 2x2 Winner Strategy Grid
					Text(
						text = stringResource(R.string.enhance_winner_strategy_label),
						style = MaterialTheme.typography.labelSmall,
						fontWeight = FontWeight.Bold,
						letterSpacing = 0.8.sp,
						color = NemuriMuted,
					)

					val strategyRows = remember {
						listOf(
							listOf(DedupeWinnerRule.MOST_CHAPTERS, DedupeWinnerRule.FURTHEST_READ),
							listOf(DedupeWinnerRule.HEALTHY_SOURCE, DedupeWinnerRule.HIGHEST_RATING),
						)
					}
					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
						strategyRows.forEach { rowRules ->
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.spacedBy(8.dp),
							) {
								rowRules.forEach { rule ->
									val active = rule == state.winnerRule
									val labelRes = when (rule) {
										DedupeWinnerRule.MOST_CHAPTERS -> R.string.enhance_winner_most_chapters
										DedupeWinnerRule.FURTHEST_READ -> R.string.enhance_winner_furthest_read
										DedupeWinnerRule.HEALTHY_SOURCE -> R.string.enhance_winner_healthy_source
										DedupeWinnerRule.HIGHEST_RATING -> R.string.enhance_winner_highest_rating
									}
									Surface(
										shape = RoundedCornerShape(12.dp),
										color = if (active) NemuriCyan.copy(alpha = 0.18f) else ObsidianInnerSurface,
										border = BorderStroke(
											width = 1.dp,
											color = if (active) NemuriCyan else Color.White.copy(alpha = 0.08f),
										),
										modifier = Modifier
											.weight(1f)
											.clip(RoundedCornerShape(12.dp))
											.clickable { onSelectWinnerRule(rule) },
									) {
										Text(
											text = stringResource(labelRes),
											style = MaterialTheme.typography.labelMedium,
											fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
											color = if (active) NemuriCyan else Color(0xFFD5DAE5),
											textAlign = TextAlign.Center,
											maxLines = 1,
											overflow = TextOverflow.Ellipsis,
											modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
										)
									}
								}
							}
						}
					}

					// Primary Cyan Button: Merge All (N)
					Button(
						onClick = onMergeAll,
						enabled = state.duplicatePairs.isNotEmpty() && !state.isBusyMutatingDuplicates,
						shape = RoundedCornerShape(14.dp),
						colors = ButtonDefaults.buttonColors(
							containerColor = NemuriCyan,
							contentColor = NemuriDarkInk,
							disabledContainerColor = NemuriCyan.copy(alpha = 0.22f),
							disabledContentColor = Color.White.copy(alpha = 0.45f),
						),
						modifier = Modifier
							.fillMaxWidth()
							.height(48.dp),
					) {
						if (state.isBusyMutatingDuplicates) {
							CircularProgressIndicator(
								strokeWidth = 2.dp,
								color = NemuriDarkInk,
								modifier = Modifier.size(18.dp),
							)
							Spacer(Modifier.width(8.dp))
						} else {
							Icon(
								painter = painterResource(R.drawable.ic_merge),
								contentDescription = null,
								modifier = Modifier.size(18.dp),
							)
							Spacer(Modifier.width(8.dp))
						}
						Text(
							text = stringResource(R.string.enhance_merge_all_button, state.duplicatePairs.size),
							fontWeight = FontWeight.Bold,
						)
					}

					// Configure & Select Sources + Live Priority Summary
					OutlinedButton(
						onClick = onOpenPrioritySelector,
						shape = RoundedCornerShape(14.dp),
						border = BorderStroke(1.dp, NemuriCyan.copy(alpha = 0.45f)),
						colors = ButtonDefaults.outlinedButtonColors(contentColor = NemuriCyan),
						modifier = Modifier
							.fillMaxWidth()
							.height(44.dp),
					) {
						Icon(
							painter = painterResource(R.drawable.ic_settings),
							contentDescription = null,
							modifier = Modifier.size(16.dp),
						)
						Spacer(Modifier.width(8.dp))
						Text(
							text = stringResource(R.string.enhance_configure_sources_button),
							fontWeight = FontWeight.SemiBold,
						)
					}

					Row(
						modifier = Modifier.fillMaxWidth(),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.SpaceBetween,
					) {
						Text(
							text = state.prioritySummary,
							style = MaterialTheme.typography.labelSmall,
							color = NemuriMuted,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.weight(1f),
						)
						if (state.ignoredPairsCount > 0) {
							TextButton(
								onClick = onResetIgnored,
								contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
							) {
								Text(
									text = stringResource(R.string.enhance_reset_ignored, state.ignoredPairsCount),
									style = MaterialTheme.typography.labelSmall,
									color = NemuriCyan,
								)
							}
						}
					}
				}
			}
		}

		// Loading / Empty / Side-by-Side Comparison Cards
		if (state.isScanningDuplicates) {
			item(key = "dedupe_loading") {
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.padding(vertical = 40.dp),
					contentAlignment = Alignment.Center,
				) {
					CircularProgressIndicator(color = NemuriCyan, strokeWidth = 2.5.dp)
				}
			}
		} else if (state.duplicatePairs.isEmpty()) {
			item(key = "dedupe_empty") {
				Surface(
					shape = RoundedCornerShape(22.dp),
					color = ObsidianCardSurface,
					border = BorderStroke(1.dp, HealthyGreen.copy(alpha = 0.35f)),
					modifier = Modifier.fillMaxWidth(),
				) {
					Column(
						modifier = Modifier
							.fillMaxWidth()
							.padding(24.dp),
						horizontalAlignment = Alignment.CenterHorizontally,
						verticalArrangement = Arrangement.spacedBy(8.dp),
					) {
						Icon(
							painter = painterResource(R.drawable.ic_check),
							contentDescription = null,
							tint = HealthyGreen,
							modifier = Modifier.size(32.dp),
						)
						Text(
							text = stringResource(R.string.enhance_no_duplicates_title),
							style = MaterialTheme.typography.titleMedium,
							fontWeight = FontWeight.Bold,
							color = Color.White,
							textAlign = TextAlign.Center,
						)
						Text(
							text = stringResource(R.string.enhance_no_duplicates_subtitle),
							style = MaterialTheme.typography.bodySmall,
							color = NemuriMuted,
							textAlign = TextAlign.Center,
						)
					}
				}
			}
		} else {
			items(items = state.duplicatePairs, key = { it.pairKey }) { pair ->
				DuplicateComparisonCard(
					pair = pair,
					imageLoader = imageLoader,
					isBusy = state.isBusyMutatingDuplicates,
					onSelectPrimarySide = { isRight -> onSelectPrimarySide(pair.pairKey, isRight) },
					onIgnore = { onIgnorePair(pair.pairKey) },
					onMergePair = { onMergePair(pair) },
					onMigratePair = { onMigratePair(pair) },
					onOpenDetails = onOpenDetails,
				)
			}
		}
	}
}

@Composable
private fun DuplicateComparisonCard(
	pair: DuplicatePairItem,
	imageLoader: ImageLoader,
	isBusy: Boolean,
	onSelectPrimarySide: (Boolean) -> Unit,
	onIgnore: () -> Unit,
	onMergePair: () -> Unit,
	onMigratePair: () -> Unit,
	onOpenDetails: (Manga) -> Unit,
) {
	val similarityPercent = (pair.similarity * 100f).roundToInt().coerceIn(1, 100)

	Surface(
		shape = RoundedCornerShape(22.dp),
		color = ObsidianCardSurface,
		border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(
			modifier = Modifier.padding(14.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			// Card Header: Similarity % Pill + Ignore Button
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Surface(
					shape = RoundedCornerShape(50),
					color = NemuriCyan.copy(alpha = 0.16f),
					border = BorderStroke(1.dp, NemuriCyan.copy(alpha = 0.45f)),
				) {
					Text(
						text = stringResource(R.string.enhance_similarity_badge, similarityPercent),
						style = MaterialTheme.typography.labelSmall,
						fontWeight = FontWeight.Bold,
						color = NemuriCyan,
						modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
					)
				}

				TextButton(
					onClick = onIgnore,
					enabled = !isBusy,
					contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
					modifier = Modifier.height(30.dp),
				) {
					Text(
						text = stringResource(R.string.enhance_ignore_pair),
						style = MaterialTheme.typography.labelMedium,
						color = NemuriMuted,
					)
				}
			}

			// Side-by-Side Columns
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(10.dp),
			) {
				DuplicateCandidateColumn(
					entry = pair.left,
					isPrimary = !pair.isRightPrimary,
					imageLoader = imageLoader,
					onSelectAsPrimary = { onSelectPrimarySide(false) },
					onOpenDetails = { onOpenDetails(pair.left.manga) },
					modifier = Modifier.weight(1f),
				)
				DuplicateCandidateColumn(
					entry = pair.right,
					isPrimary = pair.isRightPrimary,
					imageLoader = imageLoader,
					onSelectAsPrimary = { onSelectPrimarySide(true) },
					onOpenDetails = { onOpenDetails(pair.right.manga) },
					modifier = Modifier.weight(1f),
				)
			}

			// Pair Action Buttons: [🔗 Merge Pair] + [⇄ Migrate]
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(10.dp),
			) {
				Button(
					onClick = onMergePair,
					enabled = !isBusy,
					shape = RoundedCornerShape(14.dp),
					colors = ButtonDefaults.buttonColors(
						containerColor = NemuriCyan,
						contentColor = NemuriDarkInk,
					),
					contentPadding = PaddingValues(horizontal = 12.dp),
					modifier = Modifier
						.weight(1f)
						.height(42.dp),
				) {
					Text(
						text = stringResource(R.string.enhance_merge_pair_button),
						style = MaterialTheme.typography.labelLarge,
						fontWeight = FontWeight.Bold,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}

				FilledTonalButton(
					onClick = onMigratePair,
					enabled = !isBusy,
					shape = RoundedCornerShape(14.dp),
					colors = ButtonDefaults.filledTonalButtonColors(
						containerColor = Color(0xFF1D2433),
						contentColor = Color.White,
					),
					contentPadding = PaddingValues(horizontal = 12.dp),
					modifier = Modifier
						.weight(1f)
						.height(42.dp),
				) {
					Text(
						text = stringResource(R.string.enhance_migrate_pair_button),
						style = MaterialTheme.typography.labelLarge,
						fontWeight = FontWeight.SemiBold,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
		}
	}
}

@Composable
private fun DuplicateCandidateColumn(
	entry: LibraryEntrySnapshot,
	isPrimary: Boolean,
	imageLoader: ImageLoader,
	onSelectAsPrimary: () -> Unit,
	onOpenDetails: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val coverModel = remember(context, entry.manga.id, entry.manga.coverUrl, entry.manga.largeCoverUrl) {
		ImageRequest.Builder(context)
			.data(entry.manga.largeCoverUrl ?: entry.manga.coverUrl)
			.mangaSourceExtra(entry.manga.source)
			.build()
	}

	Surface(
		shape = RoundedCornerShape(16.dp),
		color = if (isPrimary) NemuriCyan.copy(alpha = 0.08f) else ObsidianInnerSurface,
		border = BorderStroke(
			width = if (isPrimary) 1.5.dp else 1.dp,
			color = if (isPrimary) NemuriCyan else Color.White.copy(alpha = 0.07f),
		),
		modifier = modifier
			.clip(RoundedCornerShape(16.dp))
			.clickable(onClick = onSelectAsPrimary),
	) {
		Column(
			modifier = Modifier.padding(10.dp),
			verticalArrangement = Arrangement.spacedBy(7.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Surface(
					shape = RoundedCornerShape(50),
					color = if (isPrimary) NemuriCyan else Color.White.copy(alpha = 0.07f),
				) {
					Text(
						text = if (isPrimary) {
							stringResource(R.string.enhance_primary_badge)
						} else {
							stringResource(R.string.enhance_tap_to_set_primary)
						},
						style = MaterialTheme.typography.labelSmall,
						fontSize = 10.sp,
						fontWeight = FontWeight.Bold,
						color = if (isPrimary) NemuriDarkInk else NemuriMuted,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
					)
				}

				IconButton(
					onClick = onOpenDetails,
					modifier = Modifier.size(24.dp),
				) {
					Icon(
						painter = painterResource(R.drawable.ic_open_external),
						contentDescription = stringResource(R.string.preview),
						tint = NemuriMuted,
						modifier = Modifier.size(14.dp),
					)
				}
			}

			Box(
				modifier = Modifier
					.fillMaxWidth()
					.aspectRatio(0.74f)
					.clip(RoundedCornerShape(12.dp))
					.background(Color(0xFF0A0D14)),
			) {
				AsyncImage(
					model = coverModel,
					imageLoader = imageLoader,
					contentDescription = entry.manga.title,
					contentScale = ContentScale.Crop,
					modifier = Modifier.fillMaxSize(),
				)
				Surface(
					shape = RoundedCornerShape(6.dp),
					color = ObsidianCardSurface.copy(alpha = 0.88f),
					border = BorderStroke(
						width = 1.dp,
						color = if (entry.isHealthySource) {
							Color.White.copy(alpha = 0.14f)
						} else {
							WarningAmber.copy(alpha = 0.7f)
						},
					),
					modifier = Modifier
						.align(Alignment.BottomStart)
						.padding(6.dp),
				) {
					Text(
						text = entry.sourceTitle,
						style = MaterialTheme.typography.labelSmall,
						fontSize = 10.sp,
						fontWeight = FontWeight.SemiBold,
						color = if (entry.isHealthySource) Color.White else WarningAmber,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
					)
				}
			}

			Text(
				text = entry.manga.title,
				style = MaterialTheme.typography.labelLarge,
				fontWeight = FontWeight.SemiBold,
				color = Color.White,
				maxLines = 2,
				minLines = 2,
				overflow = TextOverflow.Ellipsis,
			)

			val chapText = if (entry.chaptersCount > 0) {
				pluralStringResource(R.plurals.chapters, entry.chaptersCount, entry.chaptersCount)
			} else {
				stringResource(R.string.no_chapters)
			}
			val progressPct = if (ReadingProgress.isValid(entry.readProgress) && entry.readProgress > 0f) {
				" • ${(entry.readProgress * 100f).roundToInt()}%"
			} else {
				""
			}
			Text(
				text = chapText + progressPct,
				style = MaterialTheme.typography.labelSmall,
				color = NemuriMuted,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

@Composable
private fun RepairTabContent(
	state: EnhanceUiState,
	imageLoader: ImageLoader,
	nestedScroll: androidx.compose.ui.input.nestedscroll.NestedScrollConnection,
	bottomPadding: androidx.compose.ui.unit.Dp,
	onScanHealth: () -> Unit,
	onOpenBulkBrokenMigration: () -> Unit,
	onRefreshItem: (RepairItem) -> Unit,
	onMigrateItem: (Manga) -> Unit,
	onLinkBackupSource: (Manga) -> Unit,
	onOpenDetails: (Manga) -> Unit,
) {
	val hasIssues = state.repairItems.isNotEmpty()
	val statusAccent = if (hasIssues) WarningAmber else HealthyGreen

	LazyColumn(
		modifier = Modifier
			.fillMaxSize()
			.nestedScroll(nestedScroll),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 6.dp,
			bottom = bottomPadding,
		),
		verticalArrangement = Arrangement.spacedBy(14.dp),
	) {
		item(key = "repair_header_card") {
			Surface(
				shape = RoundedCornerShape(22.dp),
				color = ObsidianCardSurface,
				border = BorderStroke(1.dp, statusAccent.copy(alpha = 0.4f)),
				modifier = Modifier.fillMaxWidth(),
			) {
				Column(
					modifier = Modifier.padding(18.dp),
					verticalArrangement = Arrangement.spacedBy(12.dp),
				) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(12.dp),
					) {
						Surface(
							shape = CircleShape,
							color = statusAccent.copy(alpha = 0.16f),
							modifier = Modifier.size(42.dp),
						) {
							Box(contentAlignment = Alignment.Center) {
								Icon(
									painter = painterResource(
										if (hasIssues) R.drawable.ic_sync_problem else R.drawable.ic_check,
									),
									contentDescription = null,
									tint = statusAccent,
									modifier = Modifier.size(22.dp),
								)
							}
						}
						Column(modifier = Modifier.weight(1f)) {
							Text(
								text = if (hasIssues) {
									stringResource(R.string.enhance_repair_header_issues, state.repairItems.size)
								} else {
									stringResource(R.string.enhance_repair_header_healthy)
								},
								style = MaterialTheme.typography.titleMedium,
								fontWeight = FontWeight.Bold,
								color = Color.White,
							)
							Spacer(Modifier.height(2.dp))
							Text(
								text = if (hasIssues) {
									stringResource(R.string.enhance_repair_header_issues_sub)
								} else {
									stringResource(R.string.enhance_repair_header_healthy_sub)
								},
								style = MaterialTheme.typography.bodySmall,
								color = NemuriMuted,
							)
						}
					}

					if (state.isScanningHealth && state.healthScanTotal > 0) {
						val progress = (state.healthScanDone.toFloat() / state.healthScanTotal.toFloat())
							.coerceIn(0f, 1f)
						LinearProgressIndicator(
							progress = { progress },
							color = NemuriCyan,
							trackColor = Color.White.copy(alpha = 0.10f),
							modifier = Modifier
								.fillMaxWidth()
								.height(6.dp)
								.clip(RoundedCornerShape(50)),
						)
					}

					Button(
						onClick = onScanHealth,
						enabled = !state.isScanningHealth,
						shape = RoundedCornerShape(14.dp),
						colors = ButtonDefaults.buttonColors(
							containerColor = NemuriCyan,
							contentColor = NemuriDarkInk,
						),
						modifier = Modifier
							.fillMaxWidth()
							.height(46.dp),
					) {
						if (state.isScanningHealth) {
							CircularProgressIndicator(
								strokeWidth = 2.dp,
								color = NemuriDarkInk,
								modifier = Modifier.size(18.dp),
							)
							Spacer(Modifier.width(8.dp))
							Text(
								text = stringResource(
									R.string.enhance_scanning_progress,
									state.healthScanDone,
									state.healthScanTotal,
								),
								fontWeight = FontWeight.Bold,
							)
						} else {
							Icon(
								painter = painterResource(R.drawable.ic_sync),
								contentDescription = null,
								modifier = Modifier.size(18.dp),
							)
							Spacer(Modifier.width(8.dp))
							Text(
								text = stringResource(R.string.enhance_scan_library_health),
								fontWeight = FontWeight.Bold,
							)
						}
					}

					OutlinedButton(
						onClick = onOpenBulkBrokenMigration,
						shape = RoundedCornerShape(14.dp),
						border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
						colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
						modifier = Modifier
							.fillMaxWidth()
							.height(42.dp),
					) {
						Icon(
							painter = painterResource(R.drawable.ic_swap),
							contentDescription = null,
							tint = NemuriCyan,
							modifier = Modifier.size(16.dp),
						)
						Spacer(Modifier.width(8.dp))
						Text(
							text = stringResource(R.string.enhance_open_bulk_autofix),
							fontWeight = FontWeight.SemiBold,
						)
					}
				}
			}
		}

		items(items = state.repairItems, key = { it.manga.id }) { item ->
			RepairIssueCard(
				item = item,
				imageLoader = imageLoader,
				onRefresh = { onRefreshItem(item) },
				onMigrate = { onMigrateItem(item.manga) },
				onLinkBackup = { onLinkBackupSource(item.manga) },
				onOpenDetails = { onOpenDetails(item.manga) },
			)
		}
	}
}

@Composable
private fun RepairIssueCard(
	item: RepairItem,
	imageLoader: ImageLoader,
	onRefresh: () -> Unit,
	onMigrate: () -> Unit,
	onLinkBackup: () -> Unit,
	onOpenDetails: () -> Unit,
) {
	val context = LocalContext.current
	val coverModel = remember(context, item.manga.id, item.manga.coverUrl, item.manga.largeCoverUrl) {
		ImageRequest.Builder(context)
			.data(item.manga.largeCoverUrl ?: item.manga.coverUrl)
			.mangaSourceExtra(item.manga.source)
			.build()
	}
	val issueColor = when (item.issueKind) {
		RepairIssueKind.UNAVAILABLE_SOURCE,
		RepairIssueKind.UNREACHABLE_SOURCE -> Color(0xFFF87171)
		RepairIssueKind.ZERO_CHAPTERS,
		RepairIssueKind.MISSING_COVER -> WarningAmber
	}

	Surface(
		shape = RoundedCornerShape(20.dp),
		color = ObsidianCardSurface,
		border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(
			modifier = Modifier.padding(14.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.clip(RoundedCornerShape(12.dp))
					.clickable(onClick = onOpenDetails),
				verticalAlignment = Alignment.CenterVertically,
			) {
				AsyncImage(
					model = coverModel,
					imageLoader = imageLoader,
					contentDescription = item.manga.title,
					contentScale = ContentScale.Crop,
					modifier = Modifier
						.width(58.dp)
						.aspectRatio(0.72f)
						.clip(RoundedCornerShape(10.dp))
						.background(ObsidianInnerSurface),
				)
				Spacer(Modifier.width(12.dp))
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = item.manga.title,
						style = MaterialTheme.typography.titleSmall,
						fontWeight = FontWeight.Bold,
						color = Color.White,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
					Spacer(Modifier.height(4.dp))
					Surface(
						shape = RoundedCornerShape(6.dp),
						color = Color.White.copy(alpha = 0.07f),
					) {
						Text(
							text = item.sourceTitle,
							style = MaterialTheme.typography.labelSmall,
							color = Color(0xFFD5DAE5),
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
						)
					}
					Spacer(Modifier.height(5.dp))
					Text(
						text = item.reasonText,
						style = MaterialTheme.typography.bodySmall,
						color = issueColor,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}

			// 3 Action Pills: [Refresh] [Migrate] [Link Backup Source]
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				OutlinedButton(
					onClick = onRefresh,
					enabled = !item.isRefreshing,
					shape = RoundedCornerShape(12.dp),
					border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
					colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
					contentPadding = PaddingValues(horizontal = 10.dp),
					modifier = Modifier.height(36.dp),
				) {
					if (item.isRefreshing) {
						CircularProgressIndicator(
							strokeWidth = 2.dp,
							color = NemuriCyan,
							modifier = Modifier.size(14.dp),
						)
					} else {
						Text(
							text = stringResource(R.string.enhance_repair_refresh),
							style = MaterialTheme.typography.labelMedium,
							maxLines = 1,
						)
					}
				}

				FilledTonalButton(
					onClick = onMigrate,
					shape = RoundedCornerShape(12.dp),
					colors = ButtonDefaults.filledTonalButtonColors(
						containerColor = Color(0xFF1D2433),
						contentColor = Color.White,
					),
					contentPadding = PaddingValues(horizontal = 10.dp),
					modifier = Modifier.height(36.dp),
				) {
					Text(
						text = stringResource(R.string.enhance_repair_migrate),
						style = MaterialTheme.typography.labelMedium,
						maxLines = 1,
					)
				}

				Button(
					onClick = onLinkBackup,
					shape = RoundedCornerShape(12.dp),
					colors = ButtonDefaults.buttonColors(
						containerColor = NemuriCyan,
						contentColor = NemuriDarkInk,
					),
					contentPadding = PaddingValues(horizontal = 10.dp),
					modifier = Modifier
						.weight(1f)
						.height(36.dp),
				) {
					Text(
						text = stringResource(R.string.enhance_repair_link_backup),
						style = MaterialTheme.typography.labelMedium,
						fontWeight = FontWeight.Bold,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
		}
	}
}

@Composable
private fun UpscalerTabContent(
	state: EnhanceUiState,
	nestedScroll: androidx.compose.ui.input.nestedscroll.NestedScrollConnection,
	bottomPadding: androidx.compose.ui.unit.Dp,
	onSelectPreset: (ReaderUpscalePreset) -> Unit,
	onToggleActive: (Boolean) -> Unit,
	onToggle32Bit: (Boolean) -> Unit,
	onSelectSample: (org.koitharu.kotatsu.enhance.domain.UpscaleSampleItem) -> Unit,
	onOpenReaderSettings: () -> Unit,
) {
	val isActive = state.upscalePreset != ReaderUpscalePreset.OFF

	LazyColumn(
		modifier = Modifier
			.fillMaxSize()
			.nestedScroll(nestedScroll),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 6.dp,
			bottom = bottomPadding,
		),
		verticalArrangement = Arrangement.spacedBy(14.dp),
	) {
		item(key = "upscaler_main_card") {
			Surface(
				shape = RoundedCornerShape(22.dp),
				color = ObsidianCardSurface,
				border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
				modifier = Modifier.fillMaxWidth(),
			) {
				Column(
					modifier = Modifier.padding(18.dp),
					verticalArrangement = Arrangement.spacedBy(14.dp),
				) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.SpaceBetween,
					) {
						Row(
							verticalAlignment = Alignment.CenterVertically,
							modifier = Modifier.weight(1f),
						) {
							Icon(
								painter = painterResource(R.drawable.ic_sparkles),
								contentDescription = null,
								tint = NemuriCyan,
								modifier = Modifier.size(22.dp),
							)
							Spacer(Modifier.width(10.dp))
							Column {
								Text(
									text = stringResource(R.string.enhance_reader_upscale),
									style = MaterialTheme.typography.titleMedium,
									fontWeight = FontWeight.Bold,
									color = Color.White,
								)
								Text(
									text = stringResource(state.upscalePreset.titleResId),
									style = MaterialTheme.typography.labelSmall,
									color = if (isActive) NemuriCyan else NemuriMuted,
								)
							}
						}
						Switch(
							checked = isActive,
							onCheckedChange = onToggleActive,
							colors = SwitchDefaults.colors(
								checkedThumbColor = NemuriDarkInk,
								checkedTrackColor = NemuriCyan,
							),
						)
					}

					// Interactive Before / After Split-Screen Slider Preview
					UpscaleSplitComparisonFrame(
						sourceBitmap = state.sampleBitmap,
						selectedPreset = state.upscalePreset,
						fallbackPreviewPreset = state.previewUpscalePreset,
						isLoading = state.isLoadingSampleBitmap,
						aspectRatio = 0.82f,
					)

					// Real Library Sample Picker Chips (when user has favourites with covers)
					if (state.upscaleSamples.size > 1) {
						Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
							Text(
								text = stringResource(R.string.upscale_sample_source_label),
								style = MaterialTheme.typography.labelSmall,
								fontWeight = FontWeight.SemiBold,
								color = NemuriMuted,
							)
							LazyRow(
								horizontalArrangement = Arrangement.spacedBy(8.dp),
							) {
								items(items = state.upscaleSamples, key = { it.mangaId }) { sample ->
									val selected = sample.mangaId == state.selectedSampleId
									Surface(
										shape = RoundedCornerShape(50),
										color = if (selected) {
											NemuriCyan.copy(alpha = 0.18f)
										} else {
											ObsidianInnerSurface
										},
										border = BorderStroke(
											width = 1.dp,
											color = if (selected) NemuriCyan else Color.White.copy(alpha = 0.08f),
										),
										modifier = Modifier
											.clip(RoundedCornerShape(50))
											.clickable { onSelectSample(sample) },
									) {
										Text(
											text = sample.title,
											style = MaterialTheme.typography.labelSmall,
											fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
											color = if (selected) NemuriCyan else Color(0xFFD5DAE5),
											maxLines = 1,
											overflow = TextOverflow.Ellipsis,
											modifier = Modifier
												.width(132.dp)
												.padding(horizontal = 10.dp, vertical = 6.dp),
										)
									}
								}
							}
						}
					}

					// 4-Preset Selector Grid
					UpscalePresetGridSelector(
						selectedPreset = state.upscalePreset,
						onSelectPreset = onSelectPreset,
					)

					Text(
						text = stringResource(R.string.enhance_reader_upscale_summary),
						style = MaterialTheme.typography.bodySmall,
						color = NemuriMuted,
					)

					HorizontalDivider(color = Color.White.copy(alpha = 0.07f))

					// 32-bit Enhanced Colors Toggle
					Row(
						modifier = Modifier.fillMaxWidth(),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.SpaceBetween,
					) {
						Column(
							modifier = Modifier
								.weight(1f)
								.padding(end = 12.dp),
						) {
							Text(
								text = stringResource(R.string.enhanced_colors),
								style = MaterialTheme.typography.bodyMedium,
								fontWeight = FontWeight.SemiBold,
								color = Color.White,
							)
							Text(
								text = stringResource(R.string.enhanced_colors_summary),
								style = MaterialTheme.typography.bodySmall,
								color = NemuriMuted,
							)
						}
						Switch(
							checked = state.is32BitColor,
							onCheckedChange = onToggle32Bit,
							colors = SwitchDefaults.colors(
								checkedThumbColor = NemuriDarkInk,
								checkedTrackColor = NemuriCyan,
							),
						)
					}

					OutlinedButton(
						onClick = onOpenReaderSettings,
						shape = RoundedCornerShape(14.dp),
						border = BorderStroke(1.dp, NemuriCyan.copy(alpha = 0.45f)),
						colors = ButtonDefaults.outlinedButtonColors(contentColor = NemuriCyan),
						modifier = Modifier
							.fillMaxWidth()
							.height(44.dp),
					) {
						Icon(
							painter = painterResource(R.drawable.ic_tune),
							contentDescription = null,
							modifier = Modifier.size(16.dp),
						)
						Spacer(Modifier.width(8.dp))
						Text(
							text = stringResource(R.string.enhance_open_reader_settings),
							fontWeight = FontWeight.SemiBold,
						)
					}
				}
			}
		}
	}
}
