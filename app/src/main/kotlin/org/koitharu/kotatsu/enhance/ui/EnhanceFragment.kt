package org.koitharu.kotatsu.enhance.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.compose.NemuriBrandLogo
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref

@AndroidEntryPoint
class EnhanceFragment : Fragment() {

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				EnhanceHubScreen(
					onOpenMigration = {
						startActivity(
							Intent(requireContext(), SettingsActivity::class.java).apply {
								action = SettingsActivity.ACTION_BROKEN_SOURCES
							},
						)
					},
					onOpenReaderSettings = { router.openReaderSettings() },
					onOpenExtensions = { router.openSourcesCatalog() },
				)
			}
		}
	}
}

@Composable
private fun EnhanceHubScreen(
	onOpenMigration: () -> Unit,
	onOpenReaderSettings: () -> Unit,
	onOpenExtensions: () -> Unit,
) {
	val cs = MaterialTheme.colorScheme
	val nestedScroll = rememberNestedScrollInteropConnection()
	val bottomInset = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

	var duplicateCheck by rememberBooleanPref(AppSettings.KEY_CHECK_DUPLICATES, true)
	var duplicateMigrate by rememberBooleanPref(AppSettings.KEY_MIGRATE_DUPLICATE_PROGRESS, true)
	var readerUpscale by rememberBooleanPref(AppSettings.KEY_READER_UPSCALE, false)
	var color32Bit by rememberBooleanPref(AppSettings.KEY_32BIT_COLOR, false)

	Column(
		modifier = Modifier
			.fillMaxSize()
			.background(cs.background)
			.nestedScroll(nestedScroll)
			.verticalScroll(rememberScrollState())
			.padding(
				start = 16.dp,
				end = 16.dp,
				top = 8.dp,
				bottom = bottomInset + 24.dp,
			),
		verticalArrangement = Arrangement.spacedBy(14.dp),
	) {
		Surface(
			shape = RoundedCornerShape(24.dp),
			color = cs.surfaceContainer,
			border = BorderStroke(1.dp, cs.outlineVariant),
			modifier = Modifier.fillMaxWidth(),
		) {
			Row(
				modifier = Modifier.padding(20.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				NemuriBrandLogo(
					modifier = Modifier
						.size(56.dp)
						.clip(RoundedCornerShape(16.dp)),
				)
				Spacer(Modifier.width(16.dp))
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = stringResource(R.string.enhance),
						style = MaterialTheme.typography.titleLarge,
						fontWeight = FontWeight.Bold,
						color = cs.onSurface,
					)
					Spacer(Modifier.height(4.dp))
					Text(
						text = stringResource(R.string.enhance_hub_subtitle),
						style = MaterialTheme.typography.bodyMedium,
						color = cs.onSurfaceVariant,
					)
				}
			}
		}

		// Library Hygiene & Duplicate Detection
		Surface(
			shape = RoundedCornerShape(24.dp),
			color = cs.surfaceContainer,
			border = BorderStroke(1.dp, cs.outlineVariant),
			modifier = Modifier.fillMaxWidth(),
		) {
			Column(
				modifier = Modifier.padding(20.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Icon(
						painter = painterResource(R.drawable.ic_sparkles),
						contentDescription = null,
						tint = cs.primary,
						modifier = Modifier.size(22.dp),
					)
					Spacer(Modifier.width(10.dp))
					Text(
						text = stringResource(R.string.enhance_source_migration),
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.SemiBold,
						color = cs.onSurface,
					)
				}
				Text(
					text = stringResource(R.string.enhance_source_migration_summary),
					style = MaterialTheme.typography.bodySmall,
					color = cs.onSurfaceVariant,
				)
				EnhanceToggleRow(
					title = stringResource(R.string.duplicates_check),
					checked = duplicateCheck,
					onCheckedChange = { duplicateCheck = it },
				)
				EnhanceToggleRow(
					title = stringResource(R.string.duplicates_migrate_progress),
					checked = duplicateMigrate,
					enabled = duplicateCheck,
					onCheckedChange = { duplicateMigrate = it },
				)
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(10.dp),
				) {
					Button(
						onClick = onOpenMigration,
						modifier = Modifier.weight(1f),
						colors = ButtonDefaults.buttonColors(
							containerColor = cs.primary,
							contentColor = cs.onPrimary,
						),
					) {
						Text(text = stringResource(R.string.enhance_open_migration))
					}
					FilledTonalButton(
						onClick = onOpenExtensions,
					) {
						Text(text = stringResource(R.string.enhance_open_extensions))
					}
				}
			}
		}

		// Reader Upscaling & Rendering Engine
		Surface(
			shape = RoundedCornerShape(24.dp),
			color = cs.surfaceContainer,
			border = BorderStroke(1.dp, cs.outlineVariant),
			modifier = Modifier.fillMaxWidth(),
		) {
			Column(
				modifier = Modifier.padding(20.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Icon(
						painter = painterResource(R.drawable.ic_tune),
						contentDescription = null,
						tint = cs.primary,
						modifier = Modifier.size(22.dp),
					)
					Spacer(Modifier.width(10.dp))
					Text(
						text = stringResource(R.string.enhance_reader_upscale),
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.SemiBold,
						color = cs.onSurface,
					)
				}
				Text(
					text = stringResource(R.string.enhance_reader_upscale_summary),
					style = MaterialTheme.typography.bodySmall,
					color = cs.onSurfaceVariant,
				)
				EnhanceToggleRow(
					title = stringResource(R.string.reader_upscale),
					checked = readerUpscale,
					onCheckedChange = { readerUpscale = it },
				)
				HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
				EnhanceToggleRow(
					title = stringResource(R.string.enhanced_colors),
					checked = color32Bit,
					onCheckedChange = { color32Bit = it },
				)
				FilledTonalButton(
					onClick = onOpenReaderSettings,
					modifier = Modifier.fillMaxWidth(),
				) {
					Text(text = stringResource(R.string.enhance_open_reader_settings))
				}
			}
		}
	}
}

@Composable
private fun EnhanceToggleRow(
	title: String,
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
	enabled: Boolean = true,
) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Box(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
			Text(
				text = title,
				style = MaterialTheme.typography.bodyMedium,
				color = if (enabled) {
					MaterialTheme.colorScheme.onSurface
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
				},
			)
		}
		Switch(
			checked = checked,
			onCheckedChange = onCheckedChange,
			enabled = enabled,
		)
	}
}
