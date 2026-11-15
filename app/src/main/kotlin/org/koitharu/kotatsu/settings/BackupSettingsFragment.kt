package org.koitharu.kotatsu.settings

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backup.MihonBackupExporter
import org.koitharu.kotatsu.backup.MihonBackupManager
import org.koitharu.kotatsu.backup.MihonBackupManager.Options
import org.koitharu.kotatsu.backup.MihonBackupManager.RestoreReport
import org.koitharu.kotatsu.backup.local.domain.BackupUtils
import org.koitharu.kotatsu.backup.local.ui.backup.BackupService
import org.koitharu.kotatsu.backup.local.ui.periodical.PeriodicalBackupSettingsFragment
import org.koitharu.kotatsu.backup.local.ui.restore.RestoreDialogFragment
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.kotatsumigration.domain.KotatsuMigrationManager
import org.koitharu.kotatsu.kotatsumigration.domain.MigrationState
import org.koitharu.kotatsu.kotatsumigration.ui.KotatsuMigrationService
import org.koitharu.kotatsu.kotatsumigration.ui.showExtensionInstallPromptDialog
import org.koitharu.kotatsu.kotatsumigration.ui.showKotatsuMigrationCompleteDialog
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.NavigationSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import javax.inject.Inject

private val CapsuleBg = Color(0xFF12161F)
private val CapsuleCyan = Color(0xFF5CE1E6)
private val CapsuleError = Color(0xFFF87171)

@AndroidEntryPoint
class BackupSettingsFragment : BaseComposeSettingsFragment(R.string.backup_restore) {

	@Inject
	lateinit var backupManager: MihonBackupManager

	@Inject
	lateinit var migrationManager: KotatsuMigrationManager

	@Inject
	lateinit var mihonExporter: MihonBackupExporter

	private val tachibkProgressState = MutableStateFlow<Pair<Int, Int>?>(null)
	private val capsuleToastState = MutableStateFlow<Pair<String, Boolean>?>(null)

	private val restoreMihonBackupLauncher = registerForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri != null) {
			runMihonRestoreJob(uri, options = Options())
		}
	}

	private val createLocalBackupLauncher = registerForActivityResult(
		ActivityResultContracts.CreateDocument(BackupUtils.MIME_TYPE),
	) { uri ->
		if (uri != null && BackupService.start(requireContext(), uri)) {
			showBottomCapsuleToast(getString(R.string.creating_backup))
		}
	}

	private val exportMihonBackupLauncher = registerForActivityResult(
		ActivityResultContracts.CreateDocument(MihonBackupExporter.MIME_TYPE),
	) { uri ->
		if (uri != null) {
			runMihonExportJob(uri)
		}
	}

	private val restoreLocalBackupLauncher = registerForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri != null) {
			RestoreDialogFragment.show(parentFragmentManager, uri)
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
				val migrationState by migrationManager.state.collectAsState()
				val tachibkProgress by tachibkProgressState.collectAsState()
				val capsuleToast by capsuleToastState.collectAsState()

				LaunchedEffect(capsuleToast) {
					if (capsuleToast != null) {
						delay(4_200L)
						capsuleToastState.value = null
					}
				}

				val migrationSubtitle = when (val s = migrationState) {
					is MigrationState.Running -> stringResource(
						R.string.kotatsu_migration_progress,
						s.done,
						s.total,
					)

					is MigrationState.Finished -> stringResource(
						R.string.kotatsu_migration_result,
						s.summary.converted,
						s.summary.total,
					)

					MigrationState.Idle -> stringResource(R.string.migrate_from_kotatsu_summary)
				}

				val tachibkSubtitle = tachibkProgress?.let { (done, total) ->
					stringResource(R.string.restore_tachibk_progress, done, total)
				} ?: stringResource(R.string.restore_tachiyomi_summary)

				Box(modifier = Modifier.fillMaxSize()) {
					BackupScreen(
						onCreateBackup = {
							createLocalBackupLauncher.launch(
								BackupUtils.generateFileName(requireContext()),
							)
						},
						onRestoreLocal = {
							restoreLocalBackupLauncher.launch(BACKUP_PICKER_MIME_TYPES)
						},
						onOpenPeriodic = {
							(activity as? SettingsActivity)?.openFragment(
								PeriodicalBackupSettingsFragment::class.java,
								null,
								isFromRoot = false,
							)
						},
						tachibkSubtitle = tachibkSubtitle,
						onRestoreFromTachiyomi = {
							restoreMihonBackupLauncher.launch(BACKUP_PICKER_MIME_TYPES)
						},
						migrationSubtitle = migrationSubtitle,
						onMigrateFromKotatsu = ::confirmAndStartKotatsuMigration,
						onExportToMihon = {
							exportMihonBackupLauncher.launch(MihonBackupExporter.generateFileName())
						},
					)

					// Floating Bottom Capsule Toast (strictly bottom-anchored — zero top notification banners)
					AnimatedVisibility(
						visible = capsuleToast != null,
						enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
						exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
						modifier = Modifier
							.align(Alignment.BottomCenter)
							.navigationBarsPadding()
							.padding(horizontal = 20.dp, vertical = 20.dp),
					) {
						val currentToast = capsuleToast
						if (currentToast != null) {
							val accent = if (currentToast.second) CapsuleError else CapsuleCyan
							Surface(
								shape = RoundedCornerShape(999.dp),
								color = CapsuleBg,
								border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
								shadowElevation = 8.dp,
							) {
								Row(
									modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
									verticalAlignment = Alignment.CenterVertically,
								) {
									Icon(
										painter = painterResource(R.drawable.ic_backup_restore),
										contentDescription = null,
										tint = accent,
										modifier = Modifier.size(16.dp),
									)
									Spacer(Modifier.width(8.dp))
									Text(
										text = currentToast.first,
										style = MaterialTheme.typography.bodySmall,
										fontWeight = FontWeight.SemiBold,
										color = Color.White,
									)
								}
							}
						}
					}
				}
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		parentFragmentManager.setFragmentResultListener(
			RestoreDialogFragment.RESULT_STARTED,
			viewLifecycleOwner,
		) { _, bundle ->
			val capsuleSummary = bundle.getString(RestoreDialogFragment.KEY_CAPSULE_SUMMARY)
			if (!capsuleSummary.isNullOrBlank()) {
				showBottomCapsuleToast(capsuleSummary)
			}
		}
		migrationManager.onStarted.observeEvent(viewLifecycleOwner) {
			showBottomCapsuleToast(getString(R.string.kotatsu_migration_started))
		}
		migrationManager.onCompleted.observeEvent(viewLifecycleOwner) { summary ->
			requireContext().showKotatsuMigrationCompleteDialog(summary)
		}
	}

	private fun showBottomCapsuleToast(message: String, isError: Boolean = false) {
		capsuleToastState.value = message to isError
	}

	private fun confirmAndStartKotatsuMigration() {
		if (migrationManager.isRunning) {
			showBottomCapsuleToast(getString(R.string.kotatsu_migration_running))
			return
		}
		buildAlertDialog(requireContext()) {
			setTitle(R.string.migrate_from_kotatsu)
			setMessage(R.string.migrate_from_kotatsu_confirm)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.migrate_from_kotatsu) { _, _ ->
				if (KotatsuMigrationService.start(requireContext())) {
					showBottomCapsuleToast(getString(R.string.kotatsu_migration_running))
				}
			}
		}.show()
	}

	private fun runMihonExportJob(uri: Uri) {
		lifecycleScope.launch {
			runCatching {
				mihonExporter.export(uri)
			}.fold(
				onSuccess = { report ->
					val msg = if (report.skippedCount > 0) {
						getString(R.string.export_to_mihon_done_partial, report.exportedCount, report.skippedCount)
					} else {
						getString(R.string.export_to_mihon_done, report.exportedCount)
					}
					showBottomCapsuleToast(msg)
				},
				onFailure = { err ->
					showBottomCapsuleToast(err.getDisplayMessage(resources), isError = true)
				},
			)
		}
	}

	private fun runMihonRestoreJob(uri: Uri, options: Options) {
		lifecycleScope.launch {
			var restoreReport: RestoreReport? = null
			val result = runCatching {
				restoreReport = backupManager.restoreBackup(
					uri = uri,
					options = options,
					onProgress = { done, total ->
						tachibkProgressState.value = done to total
					},
				)
			}
			tachibkProgressState.value = null
			result.fold(
				onSuccess = {
					val r = restoreReport
					if (r != null) {
						showBottomCapsuleToast(
							getString(
								R.string.restore_tachibk_capsule_summary,
								r.restoredMangaCount,
								r.restoredCategoriesCount,
								r.restoredMergedLinksCount,
							),
						)
						if (r.missingSources.isNotEmpty()) {
							requireContext().showExtensionInstallPromptDialog(r.missingSources)
						}
					} else {
						showBottomCapsuleToast(getString(R.string.data_restored_success))
					}
				},
				onFailure = { err ->
					showBottomCapsuleToast(err.getDisplayMessage(resources), isError = true)
				},
			)
		}
	}

	companion object {
		val BACKUP_PICKER_MIME_TYPES = arrayOf(
			"*/*",
			"application/octet-stream",
			"application/gzip",
			"application/x-gzip",
			BackupUtils.MIME_TYPE,
		)
	}
}

@Composable
private fun BackupScreen(
	onCreateBackup: () -> Unit,
	onRestoreLocal: () -> Unit,
	onOpenPeriodic: () -> Unit,
	tachibkSubtitle: String,
	onRestoreFromTachiyomi: () -> Unit,
	migrationSubtitle: String,
	onMigrateFromKotatsu: () -> Unit,
	onExportToMihon: () -> Unit,
) {
	SettingsScaffold {
		item {
			SettingsGroup(title = stringResource(R.string.backup_restore)) {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.create_backup),
						subtitle = stringResource(R.string.create_backup_summary),
						icon = R.drawable.ic_save,
						shape = pos.shape,
						onClick = onCreateBackup,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.restore_backup),
						subtitle = stringResource(R.string.restore_summary),
						icon = R.drawable.ic_revert,
						shape = pos.shape,
						onClick = onRestoreLocal,
					)
				}
				item { pos ->
					NavigationSettingsItem(
						title = stringResource(R.string.periodic_backups),
						subtitle = stringResource(R.string.periodic_backups_summary),
						icon = R.drawable.ic_backup_restore,
						shape = pos.shape,
						onClick = onOpenPeriodic,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.other_apps)) {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.restore_from_tachiyomi),
						subtitle = tachibkSubtitle,
						icon = R.drawable.ic_revert,
						shape = pos.shape,
						onClick = onRestoreFromTachiyomi,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.migrate_from_kotatsu),
						subtitle = migrationSubtitle,
						icon = R.drawable.ic_backup_restore,
						shape = pos.shape,
						onClick = onMigrateFromKotatsu,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.export_to_mihon),
						subtitle = stringResource(R.string.export_to_mihon_summary),
						icon = R.drawable.ic_upload_file,
						shape = pos.shape,
						onClick = onExportToMihon,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}
