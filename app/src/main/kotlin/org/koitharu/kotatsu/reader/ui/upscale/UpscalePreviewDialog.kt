package org.koitharu.kotatsu.reader.ui.upscale

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.shape.MaterialShapeDrawable
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderUpscalePreset
import org.koitharu.kotatsu.core.prefs.UpscaleComputeMode
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.domain.UpscaleEffect
import org.koitharu.kotatsu.reader.ui.ReaderViewModel
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.SettingsSearchHighlight
import javax.inject.Inject
import com.google.android.material.R as materialR

private val ObsidianSheetBg = Color(0xFF12161F)
private val NemuriCyanAccent = Color(0xFF5CE1E6)

@AndroidEntryPoint
class UpscalePreviewDialog : BottomSheetDialogFragment() {

	private val viewModel by activityViewModels<ReaderViewModel>()

	@Inject
	lateinit var pageLoader: PageLoader

	@Inject
	lateinit var settings: AppSettings

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				val presetFlow = remember {
					combine(
						settings.observeAsFlow(AppSettings.KEY_READER_UPSCALE) { isReaderUpscaleEnabled },
						settings.observeAsFlow(AppSettings.KEY_READER_UPSCALE_PRESET) { readerUpscalePreset },
					) { _, preset -> preset }
				}
				val selectedPreset by presetFlow.collectAsState(initial = settings.readerUpscalePreset)
				val fallbackPreviewPreset by remember {
					settings.observeAsFlow(AppSettings.KEY_READER_UPSCALE_PRESET) { previewUpscalePreset }
				}.collectAsState(initial = settings.previewUpscalePreset)
				val computeMode by remember {
					settings.observeAsFlow(AppSettings.KEY_READER_UPSCALE_COMPUTE_MODE) { readerUpscaleComputeMode }
				}.collectAsState(initial = settings.readerUpscaleComputeMode)

				var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
				var isLoading by remember { mutableStateOf(true) }
				var errorMessage by remember { mutableStateOf<String?>(null) }

				LaunchedEffect(Unit) {
					isLoading = true
					errorMessage = null
					runCatchingCancellable {
						val page = checkNotNull(viewModel.getCurrentPage()) {
							"Cannot find current page"
						}
						val viewport = UpscaleEffect.viewportFor(page.id)
						pageLoader.loadPreviewBitmap(page = page, viewport = viewport, maxSidePx = 1280)
					}.onSuccess { bmp ->
						pageBitmap = bmp
						isLoading = false
					}.onFailure { err ->
						errorMessage = err.getDisplayMessage(resources)
						isLoading = false
					}
				}

				UpscaleSheetContent(
					sourceBitmap = pageBitmap,
					selectedPreset = selectedPreset,
					fallbackPreviewPreset = fallbackPreviewPreset,
					computeMode = computeMode,
					isLoading = isLoading,
					errorMessage = errorMessage,
					onSelectPreset = { preset ->
						settings.readerUpscalePreset = preset
					},
					onSelectComputeMode = { mode ->
						settings.readerUpscaleComputeMode = mode
					},
					onToggleActive = { active ->
						settings.isReaderUpscaleEnabled = active
					},
					onOpenSettings = {
						val highlightTitle = getString(R.string.upscale_preset_title)
						SettingsSearchHighlight.request(highlightTitle)
						startActivity(AppRouter.readerSettingsIntent(requireContext()))
						dismiss()
					},
					onClose = { dismiss() },
				)
			}
		}
	}

	override fun onStart() {
		super.onStart()
		(dialog as? BottomSheetDialog)?.let { sheetDialog ->
			sheetDialog.behavior.skipCollapsed = true
			sheetDialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
			val sheetView = sheetDialog.findViewById<View>(materialR.id.design_bottom_sheet)
			sheetView?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
			(sheetView?.background as? MaterialShapeDrawable)?.let { background ->
				val corner = resources.getDimension(R.dimen.sheet_corner_expressive)
				background.shapeAppearanceModel = background.shapeAppearanceModel.toBuilder()
					.setTopLeftCornerSize(corner)
					.setTopRightCornerSize(corner)
					.build()
			}
		}
	}

	companion object {

		private const val TAG = "UpscalePreviewDialog"

		fun show(fm: FragmentManager) {
			if (fm.findFragmentByTag(TAG) == null && !fm.isStateSaved) {
				UpscalePreviewDialog().show(fm, TAG)
			}
		}
	}
}

@Composable
private fun UpscaleSheetContent(
	sourceBitmap: Bitmap?,
	selectedPreset: ReaderUpscalePreset,
	fallbackPreviewPreset: ReaderUpscalePreset,
	computeMode: UpscaleComputeMode,
	isLoading: Boolean,
	errorMessage: String?,
	onSelectPreset: (ReaderUpscalePreset) -> Unit,
	onSelectComputeMode: (UpscaleComputeMode) -> Unit,
	onToggleActive: (Boolean) -> Unit,
	onOpenSettings: () -> Unit,
	onClose: () -> Unit,
) {
	val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
	val isActive = selectedPreset != ReaderUpscalePreset.OFF

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
			.background(ObsidianSheetBg)
			.verticalScroll(rememberScrollState())
			.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = navBottom + 20.dp),
		verticalArrangement = Arrangement.spacedBy(14.dp),
	) {
		Box(
			modifier = Modifier
				.align(Alignment.CenterHorizontally)
				.width(38.dp)
				.height(4.dp)
				.clip(CircleShape)
				.background(Color.White.copy(alpha = 0.22f)),
		)

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
					tint = NemuriCyanAccent,
					modifier = Modifier.size(20.dp),
				)
				Spacer(Modifier.width(10.dp))
				Text(
					text = stringResource(R.string.upscale_preview),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.Bold,
					color = Color.White,
				)
			}
			IconButton(
				onClick = onClose,
				modifier = Modifier.size(36.dp),
			) {
				Icon(
					painter = painterResource(R.drawable.ic_close),
					contentDescription = stringResource(R.string.close),
					tint = Color(0xFFB8C0D0),
					modifier = Modifier.size(18.dp),
				)
			}
		}

		UpscaleSplitComparisonFrame(
			sourceBitmap = sourceBitmap,
			selectedPreset = selectedPreset,
			fallbackPreviewPreset = fallbackPreviewPreset,
			computeMode = computeMode,
			isLoading = isLoading,
			errorMessage = errorMessage,
			aspectRatio = 0.84f,
		)

		UpscalePresetGridSelector(
			selectedPreset = selectedPreset,
			onSelectPreset = onSelectPreset,
		)

		UpscaleComputeModeSelector(
			selectedMode = computeMode,
			onSelectMode = onSelectComputeMode,
		)

		Text(
			text = stringResource(R.string.upscale_preview_description),
			style = MaterialTheme.typography.bodySmall,
			color = Color(0xFF94A3B8),
		)

		HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier
					.clip(RoundedCornerShape(10.dp))
					.clickable(onClick = onOpenSettings)
					.padding(vertical = 6.dp, horizontal = 4.dp),
			) {
				Icon(
					painter = painterResource(R.drawable.ic_settings),
					contentDescription = null,
					tint = NemuriCyanAccent,
					modifier = Modifier.size(16.dp),
				)
				Spacer(Modifier.width(6.dp))
				Text(
					text = stringResource(R.string.upscale_open_settings),
					style = MaterialTheme.typography.labelLarge,
					fontWeight = FontWeight.SemiBold,
					color = NemuriCyanAccent,
				)
			}

			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Text(
					text = stringResource(R.string.upscale_active_toggle),
					style = MaterialTheme.typography.labelLarge,
					color = if (isActive) NemuriCyanAccent else Color(0xFF94A3B8),
				)
				Switch(
					checked = isActive,
					onCheckedChange = onToggleActive,
					colors = SwitchDefaults.colors(
						checkedThumbColor = Color(0xFF061318),
						checkedTrackColor = NemuriCyanAccent,
					),
				)
			}
		}
	}
}
