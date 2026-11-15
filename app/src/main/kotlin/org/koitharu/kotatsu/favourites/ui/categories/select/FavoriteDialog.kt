package org.koitharu.kotatsu.favourites.ui.categories.select

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.viewModels
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.checkbox.MaterialCheckBox
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.joinToStringWithLimit
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.favourites.ui.categories.select.model.MangaCategoryItem
import org.koitharu.kotatsu.settings.compose.DropSauceTheme

private val SheetObsidianBg = Color(0xFF090C10)
private val SheetElevatedBg = Color(0xFF12161F)
private val SheetBorderSubtle = Color(0xFF1F2735)
private val NemuriCyan = Color(0xFF5CE1E6)
private val NemuriDarkInk = Color(0xFF061318)
private val NemuriMuted = Color(0xFF94A3B8)
private val RemoveRose = Color(0xFFF87171)

/**
 * Neko-style one-handed Obsidian BottomSheet (`Move to..` / `Add to..`) used for both
 * single-manga category edits and Library multi-select batch category moves.
 */
@AndroidEntryPoint
class FavoriteDialog : BottomSheetDialogFragment() {

	private val viewModel by viewModels<FavoriteDialogViewModel>()

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
				NekoCategoryMoveSheet()
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.onError.observeEvent(viewLifecycleOwner) { e ->
			Toast.makeText(context ?: return@observeEvent, e.getDisplayMessage(resources), Toast.LENGTH_SHORT).show()
		}
		viewModel.onDismissSheet.observeEvent(viewLifecycleOwner) {
			dismissAllowingStateLoss()
		}
	}

	@Composable
	private fun NekoCategoryMoveSheet() {
		val context = LocalContext.current
		val state by viewModel.uiState.collectAsState()
		val mangaSummary = remember {
			viewModel.manga.joinToStringWithLimit(context, 72) { it.title }
		}
		var showNewCategoryCreator by rememberSaveable { mutableStateOf(false) }
		var newCategoryDraft by rememberSaveable { mutableStateOf("") }

		Surface(
			modifier = Modifier
				.fillMaxWidth()
				.imePadding(),
			shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
			color = SheetObsidianBg,
			border = BorderStroke(1.dp, SheetBorderSubtle),
		) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.navigationBarsPadding()
					.padding(top = 10.dp, bottom = 14.dp),
			) {
				// Drag handle pill
				Box(
					modifier = Modifier
						.align(Alignment.CenterHorizontally)
						.width(36.dp)
						.height(4.dp)
						.clip(CircleShape)
						.background(NemuriMuted.copy(alpha = 0.35f)),
				)

				Spacer(Modifier.height(12.dp))

				// Top Header Row: "Move to.." / "Add to.." on left + "+ New category" on right
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 20.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Column(modifier = Modifier.weight(1f)) {
						Text(
							text = stringResource(
								if (state.isAlreadyInLibrary) R.string.move_to_categories else R.string.add_to_categories,
							),
							style = MaterialTheme.typography.titleMedium,
							fontWeight = FontWeight.Bold,
							color = Color.White,
						)
						if (mangaSummary.isNotBlank()) {
							Text(
								text = mangaSummary,
								style = MaterialTheme.typography.bodySmall,
								color = NemuriMuted,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
						}
					}

					Spacer(Modifier.width(8.dp))

					OutlinedButton(
						onClick = { showNewCategoryCreator = !showNewCategoryCreator },
						shape = RoundedCornerShape(999.dp),
						border = BorderStroke(1.dp, NemuriCyan.copy(alpha = 0.45f)),
						colors = ButtonDefaults.outlinedButtonColors(contentColor = NemuriCyan),
						contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
						modifier = Modifier.height(34.dp),
					) {
						Text(
							text = stringResource(R.string.new_category_plus),
							fontSize = 12.sp,
							fontWeight = FontWeight.SemiBold,
						)
					}
				}

				// Inline quick category creator
				AnimatedVisibility(
					visible = showNewCategoryCreator,
					enter = expandVertically() + fadeIn(),
					exit = shrinkVertically() + fadeOut(),
				) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 20.dp, vertical = 10.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						OutlinedTextField(
							value = newCategoryDraft,
							onValueChange = { newCategoryDraft = it },
							placeholder = {
								Text(
									text = stringResource(R.string.enter_category_name),
									color = NemuriMuted,
									fontSize = 13.sp,
								)
							},
							singleLine = true,
							shape = RoundedCornerShape(14.dp),
							colors = OutlinedTextFieldDefaults.colors(
								focusedTextColor = Color.White,
								unfocusedTextColor = Color.White,
								focusedContainerColor = SheetElevatedBg,
								unfocusedContainerColor = SheetElevatedBg,
								focusedBorderColor = NemuriCyan,
								unfocusedBorderColor = SheetBorderSubtle,
								cursorColor = NemuriCyan,
							),
							keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
							keyboardActions = KeyboardActions(
								onDone = {
									if (newCategoryDraft.isNotBlank()) {
										viewModel.createNewCategory(newCategoryDraft)
										newCategoryDraft = ""
										showNewCategoryCreator = false
									}
								},
							),
							modifier = Modifier.weight(1f),
						)
						Spacer(Modifier.width(8.dp))
						Button(
							onClick = {
								if (newCategoryDraft.isNotBlank()) {
									viewModel.createNewCategory(newCategoryDraft)
									newCategoryDraft = ""
									showNewCategoryCreator = false
								}
							},
							enabled = newCategoryDraft.isNotBlank(),
							shape = RoundedCornerShape(999.dp),
							colors = ButtonDefaults.buttonColors(
								containerColor = NemuriCyan,
								contentColor = NemuriDarkInk,
							),
							contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
						) {
							Text(
								text = stringResource(R.string.create_category_short),
								fontWeight = FontWeight.Bold,
								fontSize = 13.sp,
							)
						}
					}
				}

				Spacer(Modifier.height(10.dp))
				HorizontalDivider(color = SheetBorderSubtle)

				// Body: compact scrollable multi-select checkbox list of user categories
				if (state.isLoading) {
					Box(
						modifier = Modifier
							.fillMaxWidth()
							.padding(vertical = 28.dp),
						contentAlignment = Alignment.Center,
					) {
						CircularProgressIndicator(
							color = NemuriCyan,
							modifier = Modifier.size(28.dp),
							strokeWidth = 2.5.dp,
						)
					}
				} else if (state.customCategories.isEmpty()) {
					Box(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 20.dp, vertical = 22.dp),
					) {
						Surface(
							shape = RoundedCornerShape(14.dp),
							color = SheetElevatedBg,
							border = BorderStroke(1.dp, SheetBorderSubtle),
							modifier = Modifier.fillMaxWidth(),
						) {
							Row(
								modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
								verticalAlignment = Alignment.CenterVertically,
							) {
								Icon(
									painter = painterResource(R.drawable.ic_list_group),
									contentDescription = null,
									tint = NemuriCyan,
									modifier = Modifier.size(18.dp),
								)
								Spacer(Modifier.width(10.dp))
								Text(
									text = "Series will stay in your Default shelf unless you add a custom category.",
									style = MaterialTheme.typography.bodySmall,
									color = NemuriMuted,
								)
							}
						}
					}
				} else {
					Column(
						modifier = Modifier
							.fillMaxWidth()
							.heightIn(max = 320.dp)
							.verticalScroll(rememberScrollState())
							.padding(horizontal = 12.dp, vertical = 8.dp),
						verticalArrangement = Arrangement.spacedBy(4.dp),
					) {
						state.customCategories.forEach { item ->
							CategoryCheckboxRow(
								item = item,
								onToggle = { viewModel.toggleCategory(item.category.id, item.checkedState) },
							)
						}
					}
				}

				// Subtle Remove from library action when editing an existing entry
				if (state.isAlreadyInLibrary) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 16.dp, vertical = 2.dp),
						horizontalArrangement = Arrangement.Start,
					) {
						TextButton(
							onClick = { viewModel.removeFromLibrary() },
							enabled = !state.isSaving,
							colors = ButtonDefaults.textButtonColors(contentColor = RemoveRose),
							contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
						) {
							Icon(
								painter = painterResource(R.drawable.ic_delete),
								contentDescription = null,
								modifier = Modifier.size(16.dp),
							)
							Spacer(Modifier.width(6.dp))
							Text(
								text = stringResource(R.string.remove_from_library),
								fontSize = 12.sp,
								fontWeight = FontWeight.Medium,
							)
						}
					}
				}

				HorizontalDivider(color = SheetBorderSubtle)
				Spacer(Modifier.height(12.dp))

				// Sticky thumb-friendly bottom bar: Cancel on bottom-left + dynamic primary pill on bottom-right
				val ctaLabel = when {
					state.checkedCustomCount == 0 -> stringResource(R.string.keep_in_default_category)
					state.isAlreadyInLibrary -> stringResource(R.string.move_action)
					else -> stringResource(R.string.save)
				}

				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 16.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					TextButton(
						onClick = { dismissAllowingStateLoss() },
						enabled = !state.isSaving,
						shape = RoundedCornerShape(999.dp),
						colors = ButtonDefaults.textButtonColors(contentColor = NemuriMuted),
						contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
					) {
						Text(
							text = stringResource(android.R.string.cancel),
							fontWeight = FontWeight.SemiBold,
							fontSize = 14.sp,
						)
					}

					Spacer(Modifier.weight(1f))

					Button(
						onClick = { viewModel.confirmSelection() },
						enabled = !state.isSaving,
						shape = RoundedCornerShape(999.dp),
						colors = ButtonDefaults.buttonColors(
							containerColor = NemuriCyan,
							contentColor = NemuriDarkInk,
						),
						contentPadding = PaddingValues(horizontal = 20.dp, vertical = 11.dp),
					) {
						Text(
							text = ctaLabel,
							fontWeight = FontWeight.Bold,
							fontSize = 14.sp,
						)
					}
				}
			}
		}
	}

	@Composable
	private fun CategoryCheckboxRow(
		item: MangaCategoryItem,
		onToggle: () -> Unit,
	) {
		val isSelected = item.checkedState != MaterialCheckBox.STATE_UNCHECKED
		Surface(
			shape = RoundedCornerShape(14.dp),
			color = if (isSelected) SheetElevatedBg else Color.Transparent,
			border = if (isSelected) BorderStroke(1.dp, NemuriCyan.copy(alpha = 0.35f)) else null,
			modifier = Modifier
				.fillMaxWidth()
				.clip(RoundedCornerShape(14.dp))
				.clickable(onClick = onToggle),
		) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(min = 48.dp)
					.padding(horizontal = 8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				TriStateCheckbox(
					state = when (item.checkedState) {
						MaterialCheckBox.STATE_CHECKED -> ToggleableState.On
						MaterialCheckBox.STATE_INDETERMINATE -> ToggleableState.Indeterminate
						else -> ToggleableState.Off
					},
					onClick = onToggle,
					colors = CheckboxDefaults.colors(
						checkedColor = NemuriCyan,
						checkmarkColor = NemuriDarkInk,
						uncheckedColor = NemuriMuted,
					),
				)
				Spacer(Modifier.width(6.dp))
				Text(
					text = item.category.title,
					style = MaterialTheme.typography.bodyLarge,
					fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
					color = Color.White,
					modifier = Modifier.weight(1f),
				)
				if (item.isTrackerEnabled && item.category.isTrackingEnabled) {
					Icon(
						painter = painterResource(R.drawable.ic_notification),
						contentDescription = null,
						tint = NemuriMuted,
						modifier = Modifier
							.padding(start = 8.dp)
							.size(16.dp),
					)
				}
				if (!item.category.isVisibleInLibrary) {
					Icon(
						painter = painterResource(R.drawable.ic_eye_off),
						contentDescription = null,
						tint = NemuriMuted,
						modifier = Modifier
							.padding(start = 8.dp)
							.size(16.dp),
					)
				}
			}
		}
	}
}
