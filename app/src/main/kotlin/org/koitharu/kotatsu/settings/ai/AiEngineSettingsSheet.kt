package org.koitharu.kotatsu.settings.ai

import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ai.AiBackendMode
import org.koitharu.kotatsu.core.ai.AiComputeDelegate
import org.koitharu.kotatsu.core.ai.AiRouterConfig
import org.koitharu.kotatsu.core.ai.CloudAiProvider
import org.koitharu.kotatsu.core.ai.GeminiCloudModel
import org.koitharu.kotatsu.core.ai.OllamaEndpointPreset
import org.koitharu.kotatsu.core.ai.PluggableAiRouter
import androidx.compose.ui.text.style.TextAlign
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import javax.inject.Inject

private val ObsidianSheetBg = Color(0xFF090C10)
private val ObsidianCardBg = Color(0xFF12161F)
private val ObsidianInputBg = Color(0xFF0D1118)
private val NemuriCyan = Color(0xFF5CE1E6)
private val NemuriDarkInk = Color(0xFF061318)
private val NemuriMuted = Color(0xFF94A3B8)
private val VerifiedEmerald = Color(0xFF10B981)
private val ErrorRose = Color(0xFFF87171)

/**
 * Full-screen Settings entry for `Settings -> AI Engine & LLM Router`.
 */
@AndroidEntryPoint
class AiEngineSettingsFragment : BaseComposeSettingsFragment(R.string.ai_engine_settings_title) {

	@Inject
	lateinit var aiRouter: PluggableAiRouter

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				AiEngineRouterPane(
					router = aiRouter,
					isBottomSheet = false,
					onDismiss = null,
				)
			}
		}
	}
}

/**
 * Modal Nemuri Obsidian BottomSheet for `AI Engine & LLM Router`, launched from:
 * - The `[✦]` icon on the `Enhance` Hub top bar
 * - The `[⚙]` gear icon on the `✦ Where Was I?` card in `MangaDetails`
 */
@AndroidEntryPoint
class AiEngineSettingsSheet : BottomSheetDialogFragment() {

	@Inject
	lateinit var aiRouter: PluggableAiRouter

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
				AiEngineRouterPane(
					router = aiRouter,
					isBottomSheet = true,
					onDismiss = ::dismissAllowingStateLoss,
				)
			}
		}
	}

	companion object {
		private const val TAG = "AiEngineSettingsSheet"

		fun show(fm: FragmentManager) {
			if (fm.findFragmentByTag(TAG) == null && !fm.isStateSaved) {
				AiEngineSettingsSheet().show(fm, TAG)
			}
		}
	}
}

@Composable
fun AiEngineRouterPane(
	router: PluggableAiRouter,
	isBottomSheet: Boolean,
	onDismiss: (() -> Unit)?,
) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val config by router.configFlow.collectAsState()

	var capsuleToast by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

	fun showCapsuleToast(message: String, isError: Boolean = false) {
		capsuleToast = message to isError
	}

	LaunchedEffect(capsuleToast) {
		if (capsuleToast != null) {
			delay(3200L)
			capsuleToast = null
		}
	}

	val safLauncher = rememberLauncherForActivityResult(
		contract = ActivityResultContracts.OpenDocument(),
	) { uri: Uri? ->
		if (uri != null) {
			val msg = router.setLocalModelSafUri(uri)
			showCapsuleToast(msg, isError = false)
		}
	}

	val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
	val containerModifier = if (isBottomSheet) {
		Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
			.background(ObsidianSheetBg)
			.navigationBarsPadding()
	} else {
		Modifier
			.fillMaxSize()
			.background(ObsidianSheetBg)
	}

	Box(modifier = containerModifier) {
		LazyColumn(
			modifier = Modifier.fillMaxWidth(),
			contentPadding = PaddingValues(
				start = 18.dp,
				end = 18.dp,
				top = if (isBottomSheet) 12.dp else 16.dp,
				bottom = navBottom + 88.dp,
			),
			verticalArrangement = Arrangement.spacedBy(14.dp),
		) {
			if (isBottomSheet) {
				item {
					Column(
						modifier = Modifier.fillMaxWidth(),
						horizontalAlignment = Alignment.CenterHorizontally,
					) {
						Box(
							modifier = Modifier
								.width(38.dp)
								.height(4.dp)
								.clip(CircleShape)
								.background(Color.White.copy(alpha = 0.2f)),
						)
						Spacer(Modifier.height(12.dp))
						Row(
							modifier = Modifier.fillMaxWidth(),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.SpaceBetween,
						) {
							Row(
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(10.dp),
							) {
								Surface(
									shape = RoundedCornerShape(12.dp),
									color = NemuriCyan.copy(alpha = 0.14f),
									border = BorderStroke(1.dp, NemuriCyan.copy(alpha = 0.35f)),
								) {
									Icon(
										painter = painterResource(R.drawable.ic_sparkles),
										contentDescription = null,
										tint = NemuriCyan,
										modifier = Modifier
											.padding(8.dp)
											.size(18.dp),
									)
								}
								Column {
									Text(
										text = stringResource(R.string.ai_engine_settings_title),
										style = MaterialTheme.typography.titleMedium,
										fontWeight = FontWeight.Bold,
										color = Color.White,
									)
									Text(
										text = config.activeAttributionBadge(),
										style = MaterialTheme.typography.labelSmall,
										color = NemuriCyan,
									)
								}
							}
							if (onDismiss != null) {
								IconButton(onClick = onDismiss) {
									Icon(
										painter = painterResource(R.drawable.ic_close),
										contentDescription = stringResource(R.string.close),
										tint = NemuriMuted,
									)
								}
							}
						}
					}
				}
			}

			// 1. Master Generative AI Toggle
			item {
				MasterAiSwitchCard(
					enabled = config.masterAiEnabled,
					activeBadge = config.activeAttributionBadge(),
					onToggle = { enabled ->
						router.setMasterAiEnabled(enabled)
						showCapsuleToast(
							if (enabled) "Generative AI features enabled"
							else "Generative AI features turned OFF",
						)
					},
				)
			}

			// 2. 3-Backend Selector Cards
			item {
				Text(
					text = stringResource(R.string.ai_backend_section_title),
					style = MaterialTheme.typography.labelLarge,
					fontWeight = FontWeight.SemiBold,
					color = NemuriMuted,
					modifier = Modifier.padding(start = 4.dp),
				)
			}

			item {
				Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
					BackendOptionCard(
						selected = config.backendMode == AiBackendMode.ON_DEVICE_LOCAL,
						iconRes = R.drawable.ic_sparkles,
						title = stringResource(R.string.ai_backend_local_title),
						subtitle = stringResource(R.string.ai_backend_local_subtitle),
						badge = "PRIVATE",
						onClick = { router.setBackendMode(AiBackendMode.ON_DEVICE_LOCAL) },
					)
					BackendOptionCard(
						selected = config.backendMode == AiBackendMode.OLLAMA_LAN,
						iconRes = R.drawable.ic_sync,
						title = stringResource(R.string.ai_backend_ollama_title),
						subtitle = stringResource(R.string.ai_backend_ollama_subtitle),
						badge = "LAN / 127.0.0.1",
						onClick = { router.setBackendMode(AiBackendMode.OLLAMA_LAN) },
					)
					BackendOptionCard(
						selected = config.backendMode == AiBackendMode.CLOUD_API,
						iconRes = R.drawable.ic_cloud_sync,
						title = stringResource(R.string.ai_backend_cloud_title),
						subtitle = stringResource(R.string.ai_backend_cloud_subtitle),
						badge = "FASTEST",
						onClick = { router.setBackendMode(AiBackendMode.CLOUD_API) },
					)
				}
			}

			// 3. Active Backend Detailed Configuration Card
			item {
				when (config.backendMode) {
					AiBackendMode.ON_DEVICE_LOCAL -> OnDeviceConfigCard(
						config = config,
						onSelectDelegate = { delegate ->
							router.setComputeDelegate(delegate)
							showCapsuleToast("Hardware delegate: ${delegate.label}")
						},
						onPickSafModel = { safLauncher.launch(arrayOf("*/*")) },
						onClearSafModel = {
							val msg = router.setLocalModelSafUri(null)
							showCapsuleToast(msg)
						},
					)

					AiBackendMode.OLLAMA_LAN -> OllamaConfigCard(
						config = config,
						onUpdate = { url, model, key ->
							router.updateOllamaSettings(baseUrl = url, modelId = model, apiKey = key)
						},
						onFetchModels = {
							scope.launch {
								val res = router.fetchOllamaModels()
								res.fold(
									onSuccess = { models ->
										showCapsuleToast("Loaded ${models.size} model(s): ${models.first()}")
									},
									onFailure = { err ->
										showCapsuleToast(
											err.message ?: "Could not reach Ollama server",
											isError = true,
										)
									},
								)
							}
						},
					)

					AiBackendMode.CLOUD_API -> CloudConfigCard(
						config = config,
						context = context,
						onUpdate = { provider, geminiModel, geminiKey, openAiUrl, openAiModel, openAiKey ->
							router.updateCloudSettings(
								provider = provider,
								geminiModelId = geminiModel,
								geminiApiKey = geminiKey,
								openAiBaseUrl = openAiUrl,
								openAiModelId = openAiModel,
								openAiApiKey = openAiKey,
							)
						},
						onPasted = { showCapsuleToast("Pasted API key from clipboard") },
					)
				}
			}

			// 4. Test Connection Pill Button + Status Readout
			item {
				ConnectionVerifierCard(
					config = config,
					onTestConnection = {
						scope.launch {
							val result = router.testConnection()
							showCapsuleToast(result.message, isError = !result.success)
						}
					},
				)
			}
		}

		// Floating Bottom Capsule Toast (strictly bottom-anchored — never a top banner)
		AnimatedVisibility(
			visible = capsuleToast != null,
			enter = fadeIn() + slideInVertically { it / 2 },
			exit = fadeOut() + slideOutVertically { it / 2 },
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.padding(
					start = 20.dp,
					end = 20.dp,
					bottom = navBottom + 16.dp,
				),
		) {
			val pair = capsuleToast
			val isError = pair?.second == true
			val accent = if (isError) ErrorRose else NemuriCyan
			Surface(
				shape = RoundedCornerShape(50),
				color = Color(0xF2141923),
				border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
				shadowElevation = 10.dp,
			) {
				Row(
					modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Icon(
						painter = painterResource(
							if (isError) R.drawable.ic_close_small else R.drawable.ic_sparkles,
						),
						contentDescription = null,
						tint = accent,
						modifier = Modifier.size(16.dp),
					)
					Text(
						text = pair?.first.orEmpty(),
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
private fun MasterAiSwitchCard(
	enabled: Boolean,
	activeBadge: String,
	onToggle: (Boolean) -> Unit,
) {
	Surface(
		shape = RoundedCornerShape(18.dp),
		color = ObsidianCardBg,
		border = BorderStroke(
			1.dp,
			if (enabled) NemuriCyan.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.07f),
		),
		modifier = Modifier.fillMaxWidth(),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 14.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = stringResource(R.string.ai_master_toggle_title),
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.Bold,
					color = Color.White,
				)
				Spacer(Modifier.height(2.dp))
				Text(
					text = stringResource(R.string.ai_master_toggle_subtitle),
					style = MaterialTheme.typography.bodySmall,
					color = NemuriMuted,
				)
				Spacer(Modifier.height(6.dp))
				Surface(
					shape = RoundedCornerShape(50),
					color = NemuriCyan.copy(alpha = 0.12f),
				) {
					Text(
						text = if (enabled) activeBadge else "Disabled",
						style = MaterialTheme.typography.labelSmall,
						fontWeight = FontWeight.SemiBold,
						color = if (enabled) NemuriCyan else NemuriMuted,
						modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
					)
				}
			}
			Spacer(Modifier.width(12.dp))
			Switch(
				checked = enabled,
				onCheckedChange = onToggle,
				colors = SwitchDefaults.colors(
					checkedThumbColor = NemuriDarkInk,
					checkedTrackColor = NemuriCyan,
					uncheckedThumbColor = NemuriMuted,
					uncheckedTrackColor = ObsidianInputBg,
				),
			)
		}
	}
}

@Composable
private fun BackendOptionCard(
	selected: Boolean,
	iconRes: Int,
	title: String,
	subtitle: String,
	badge: String,
	onClick: () -> Unit,
) {
	Surface(
		shape = RoundedCornerShape(16.dp),
		color = if (selected) Color(0xFF151D29) else ObsidianCardBg,
		border = BorderStroke(
			width = if (selected) 1.5.dp else 1.dp,
			color = if (selected) NemuriCyan else Color.White.copy(alpha = 0.06f),
		),
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(16.dp))
			.clickable(onClick = onClick),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Surface(
				shape = RoundedCornerShape(12.dp),
				color = if (selected) NemuriCyan.copy(alpha = 0.16f) else ObsidianInputBg,
			) {
				Icon(
					painter = painterResource(iconRes),
					contentDescription = null,
					tint = if (selected) NemuriCyan else NemuriMuted,
					modifier = Modifier
						.padding(9.dp)
						.size(18.dp),
				)
			}
			Spacer(Modifier.width(12.dp))
			Column(modifier = Modifier.weight(1f)) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Text(
						text = title,
						style = MaterialTheme.typography.bodyMedium,
						fontWeight = FontWeight.Bold,
						color = Color.White,
					)
					Surface(
						shape = RoundedCornerShape(6.dp),
						color = if (selected) NemuriCyan.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f),
					) {
						Text(
							text = badge,
							style = MaterialTheme.typography.labelSmall,
							fontSize = 10.sp,
							fontWeight = FontWeight.Bold,
							color = if (selected) NemuriCyan else NemuriMuted,
							modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
						)
					}
				}
				Spacer(Modifier.height(2.dp))
				Text(
					text = subtitle,
					style = MaterialTheme.typography.bodySmall,
					color = NemuriMuted,
				)
			}
			if (selected) {
				Spacer(Modifier.width(8.dp))
				Icon(
					painter = painterResource(R.drawable.ic_check),
					contentDescription = null,
					tint = NemuriCyan,
					modifier = Modifier.size(18.dp),
				)
			}
		}
	}
}

@Composable
private fun OnDeviceConfigCard(
	config: AiRouterConfig,
	onSelectDelegate: (AiComputeDelegate) -> Unit,
	onPickSafModel: () -> Unit,
	onClearSafModel: () -> Unit,
) {
	Surface(
		shape = RoundedCornerShape(18.dp),
		color = ObsidianCardBg,
		border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = "On-Device Local Runtime",
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.Bold,
					color = Color.White,
				)
				Surface(
					shape = RoundedCornerShape(50),
					color = if (config.hasDedicatedNpu && config.isAiCoreAvailable) {
						VerifiedEmerald.copy(alpha = 0.16f)
					} else {
						NemuriCyan.copy(alpha = 0.14f)
					},
				) {
					Text(
						text = "Active: ${config.resolvedActiveDelegate.shortBadge}",
						style = MaterialTheme.typography.labelSmall,
						fontWeight = FontWeight.SemiBold,
						color = if (config.hasDedicatedNpu && config.isAiCoreAvailable) VerifiedEmerald else NemuriCyan,
						modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
					)
				}
			}

			Text(
				text = "Device: ${config.deviceHardwareSummary}",
				style = MaterialTheme.typography.bodySmall,
				fontFamily = FontFamily.Monospace,
				color = NemuriMuted,
			)

			// 4-Way Hardware Compute Delegate Selector (Auto / GPU / CPU / NPU)
			Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Text(
					text = stringResource(R.string.ai_delegate_section_title),
					style = MaterialTheme.typography.labelMedium,
					fontWeight = FontWeight.SemiBold,
					color = NemuriMuted,
				)
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(6.dp),
				) {
					AiComputeDelegate.entries.forEach { delegate ->
						val selected = config.computeDelegate == delegate
						Surface(
							shape = RoundedCornerShape(10.dp),
							color = if (selected) NemuriCyan.copy(alpha = 0.18f) else ObsidianInputBg,
							border = BorderStroke(
								1.dp,
								if (selected) NemuriCyan else Color.White.copy(alpha = 0.08f),
							),
							modifier = Modifier
								.weight(1f)
								.clip(RoundedCornerShape(10.dp))
								.clickable { onSelectDelegate(delegate) },
						) {
							Text(
								text = delegate.shortBadge,
								style = MaterialTheme.typography.labelSmall,
								fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
								color = if (selected) NemuriCyan else Color(0xFFD5DAE5),
								textAlign = TextAlign.Center,
								maxLines = 1,
								modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
							)
						}
					}
				}
				if (!config.hasDedicatedNpu) {
					Text(
						text = "Non-NPU chipset detected — Auto cascades directly across GPU → Multi-Core CPU",
						style = MaterialTheme.typography.labelSmall,
						color = NemuriMuted,
					)
				}
			}

			Surface(
				shape = RoundedCornerShape(12.dp),
				color = ObsidianInputBg,
				border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
				modifier = Modifier.fillMaxWidth(),
			) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 12.dp, vertical = 10.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Icon(
						painter = painterResource(R.drawable.ic_folder_file),
						contentDescription = null,
						tint = NemuriCyan,
						modifier = Modifier.size(18.dp),
					)
					Spacer(Modifier.width(10.dp))
					Column(modifier = Modifier.weight(1f)) {
						Text(
							text = config.localModelFileName ?: "No .litertlm / .task / .gguf model selected",
							style = MaterialTheme.typography.bodySmall,
							fontWeight = FontWeight.SemiBold,
							color = Color.White,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						Text(
							text = if (config.localModelUri != null) {
								"Persistable SAF read URI granted"
							} else {
								"Uses deterministic spoiler-locked Reading Trail until weights are loaded"
							},
							style = MaterialTheme.typography.labelSmall,
							color = NemuriMuted,
						)
					}
					if (config.localModelUri != null) {
						OutlinedButton(
							onClick = onClearSafModel,
							contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
							border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
						) {
							Text(
								text = stringResource(R.string.ai_clear_litertlm_button),
								style = MaterialTheme.typography.labelSmall,
								color = NemuriMuted,
							)
						}
					}
				}
			}

			OutlinedButton(
				onClick = onPickSafModel,
				shape = RoundedCornerShape(12.dp),
				border = BorderStroke(1.dp, NemuriCyan.copy(alpha = 0.5f)),
				modifier = Modifier.fillMaxWidth(),
			) {
				Icon(
					painter = painterResource(R.drawable.ic_folder_file),
					contentDescription = null,
					tint = NemuriCyan,
					modifier = Modifier.size(16.dp),
				)
				Spacer(Modifier.width(8.dp))
				Text(
					text = stringResource(R.string.ai_pick_litertlm_button),
					color = NemuriCyan,
					fontWeight = FontWeight.SemiBold,
				)
			}
		}
	}
}

@Composable
private fun OllamaConfigCard(
	config: AiRouterConfig,
	onUpdate: (baseUrl: String?, modelId: String?, apiKey: String?) -> Unit,
	onFetchModels: () -> Unit,
) {
	var urlText by remember(config.ollamaBaseUrl) { mutableStateOf(config.ollamaBaseUrl) }
	var modelText by remember(config.ollamaModelId) { mutableStateOf(config.ollamaModelId) }
	var apiKeyText by remember(config.ollamaApiKey) { mutableStateOf(config.ollamaApiKey) }
	var dropdownExpanded by remember { mutableStateOf(false) }
	var showKey by remember { mutableStateOf(false) }

	Surface(
		shape = RoundedCornerShape(18.dp),
		color = ObsidianCardBg,
		border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Text(
				text = "Ollama / LAN Server Endpoint",
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.Bold,
				color = Color.White,
			)

			// Endpoint quick presets
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.horizontalScroll(rememberScrollState()),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				OllamaEndpointPreset.entries.forEach { preset ->
					val active = urlText.trimEnd('/') == preset.baseUrl.trimEnd('/')
					Surface(
						shape = RoundedCornerShape(50),
						color = if (active) NemuriCyan else ObsidianInputBg,
						border = BorderStroke(
							1.dp,
							if (active) NemuriCyan else Color.White.copy(alpha = 0.10f),
						),
						modifier = Modifier
							.clip(RoundedCornerShape(50))
							.clickable {
								urlText = preset.baseUrl
								onUpdate(preset.baseUrl, null, null)
							},
					) {
						Text(
							text = preset.label,
							style = MaterialTheme.typography.labelSmall,
							fontWeight = FontWeight.SemiBold,
							color = if (active) NemuriDarkInk else Color.White,
							modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
						)
					}
				}
			}

			OutlinedTextField(
				value = urlText,
				onValueChange = {
					urlText = it
					onUpdate(it, null, null)
				},
				label = { Text("Base URL (HTTP Loopback / RFC1918 LAN)") },
				singleLine = true,
				keyboardOptions = KeyboardOptions(
					keyboardType = KeyboardType.Uri,
					imeAction = ImeAction.Next,
				),
				colors = obsidianTextFieldColors(),
				modifier = Modifier.fillMaxWidth(),
			)

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Box(modifier = Modifier.weight(1f)) {
					OutlinedTextField(
						value = modelText,
						onValueChange = {
							modelText = it
							onUpdate(null, it, null)
						},
						label = { Text("Active Model Tag") },
						singleLine = true,
						trailingIcon = if (config.ollamaAvailableModels.isNotEmpty()) {
							{
								Text(
									text = "▼",
									color = NemuriCyan,
									modifier = Modifier
										.clickable { dropdownExpanded = true }
										.padding(8.dp),
								)
							}
						} else null,
						colors = obsidianTextFieldColors(),
						modifier = Modifier.fillMaxWidth(),
					)
					DropdownMenu(
						expanded = dropdownExpanded,
						onDismissRequest = { dropdownExpanded = false },
						modifier = Modifier.background(ObsidianCardBg),
					) {
						config.ollamaAvailableModels.forEach { tag ->
							DropdownMenuItem(
								text = {
									Text(
										text = tag,
										color = if (tag == modelText) NemuriCyan else Color.White,
										fontWeight = if (tag == modelText) FontWeight.Bold else FontWeight.Normal,
									)
								},
								onClick = {
									modelText = tag
									onUpdate(null, tag, null)
									dropdownExpanded = false
								},
							)
						}
					}
				}

				OutlinedButton(
					onClick = onFetchModels,
					enabled = !config.isBusy,
					shape = RoundedCornerShape(12.dp),
					border = BorderStroke(1.dp, NemuriCyan.copy(alpha = 0.55f)),
					modifier = Modifier.height(56.dp),
				) {
					Icon(
						painter = painterResource(R.drawable.ic_sync),
						contentDescription = null,
						tint = NemuriCyan,
						modifier = Modifier.size(16.dp),
					)
					Spacer(Modifier.width(6.dp))
					Text(
						text = stringResource(R.string.ai_fetch_models_button),
						color = NemuriCyan,
						fontWeight = FontWeight.SemiBold,
					)
				}
			}

			if (config.ollamaAvailableModels.isNotEmpty()) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.horizontalScroll(rememberScrollState()),
					horizontalArrangement = Arrangement.spacedBy(6.dp),
				) {
					config.ollamaAvailableModels.forEach { modelTag ->
						val chosen = modelTag == modelText
						Surface(
							shape = RoundedCornerShape(50),
							color = if (chosen) NemuriCyan.copy(alpha = 0.2f) else ObsidianInputBg,
							border = BorderStroke(
								1.dp,
								if (chosen) NemuriCyan else Color.White.copy(alpha = 0.08f),
							),
							modifier = Modifier
								.clip(RoundedCornerShape(50))
								.clickable {
									modelText = modelTag
									onUpdate(null, modelTag, null)
								},
						) {
							Text(
								text = modelTag,
								style = MaterialTheme.typography.labelSmall,
								color = if (chosen) NemuriCyan else NemuriMuted,
								modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
							)
						}
					}
				}
			}

			OutlinedTextField(
				value = apiKeyText,
				onValueChange = {
					apiKeyText = it
					onUpdate(null, null, it)
				},
				label = { Text("Optional LAN Bearer Key (leave blank for standard Ollama)") },
				singleLine = true,
				visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
				trailingIcon = {
					IconButton(onClick = { showKey = !showKey }) {
						Icon(
							painter = painterResource(if (showKey) R.drawable.ic_eye_off else R.drawable.ic_eye),
							contentDescription = null,
							tint = NemuriMuted,
						)
					}
				},
				colors = obsidianTextFieldColors(),
				modifier = Modifier.fillMaxWidth(),
			)
		}
	}
}

@Composable
private fun CloudConfigCard(
	config: AiRouterConfig,
	context: Context,
	onUpdate: (
		provider: CloudAiProvider?,
		geminiModel: String?,
		geminiKey: String?,
		openAiUrl: String?,
		openAiModel: String?,
		openAiKey: String?,
	) -> Unit,
	onPasted: () -> Unit,
) {
	var geminiModelText by remember(config.geminiModelId) { mutableStateOf(config.geminiModelId) }
	var geminiKeyText by remember(config.geminiApiKey) { mutableStateOf(config.geminiApiKey) }
	var openAiUrlText by remember(config.openAiBaseUrl) { mutableStateOf(config.openAiBaseUrl) }
	var openAiModelText by remember(config.openAiModelId) { mutableStateOf(config.openAiModelId) }
	var openAiKeyText by remember(config.openAiApiKey) { mutableStateOf(config.openAiApiKey) }
	var showSecret by remember { mutableStateOf(false) }

	Surface(
		shape = RoundedCornerShape(18.dp),
		color = ObsidianCardBg,
		border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			// Segmented switcher: Google Gemini | OpenAI-Compatible
			Surface(
				shape = RoundedCornerShape(14.dp),
				color = ObsidianInputBg,
				border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
				modifier = Modifier.fillMaxWidth(),
			) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(4.dp),
					horizontalArrangement = Arrangement.spacedBy(4.dp),
				) {
					CloudAiProvider.entries.forEach { provider ->
						val active = config.cloudProvider == provider
						Surface(
							shape = RoundedCornerShape(10.dp),
							color = if (active) NemuriCyan else Color.Transparent,
							modifier = Modifier
								.weight(1f)
								.clip(RoundedCornerShape(10.dp))
								.clickable { onUpdate(provider, null, null, null, null, null) },
						) {
							Text(
								text = provider.label,
								style = MaterialTheme.typography.labelMedium,
								fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
								color = if (active) NemuriDarkInk else NemuriMuted,
								modifier = Modifier.padding(vertical = 9.dp, horizontal = 8.dp),
								maxLines = 1,
							)
						}
					}
				}
			}

			if (config.cloudProvider == CloudAiProvider.GOOGLE_GEMINI) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.horizontalScroll(rememberScrollState()),
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					GeminiCloudModel.entries.forEach { preset ->
						val active = geminiModelText == preset.modelId
						Surface(
							shape = RoundedCornerShape(50),
							color = if (active) NemuriCyan else ObsidianInputBg,
							border = BorderStroke(
								1.dp,
								if (active) NemuriCyan else Color.White.copy(alpha = 0.10f),
							),
							modifier = Modifier
								.clip(RoundedCornerShape(50))
								.clickable {
									geminiModelText = preset.modelId
									onUpdate(null, preset.modelId, null, null, null, null)
								},
						) {
							Text(
								text = preset.displayName,
								style = MaterialTheme.typography.labelSmall,
								fontWeight = FontWeight.SemiBold,
								color = if (active) NemuriDarkInk else Color.White,
								modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
							)
						}
					}
				}

				OutlinedTextField(
					value = geminiModelText,
					onValueChange = {
						geminiModelText = it
						onUpdate(null, it, null, null, null, null)
					},
					label = { Text("Gemini Model ID") },
					singleLine = true,
					colors = obsidianTextFieldColors(),
					modifier = Modifier.fillMaxWidth(),
				)

				SecretKeyTextField(
					value = geminiKeyText,
					label = "Google AI Studio API Key (Keystore AES-GCM)",
					showSecret = showSecret,
					onToggleShow = { showSecret = !showSecret },
					onValueChange = {
						geminiKeyText = it
						onUpdate(null, null, it, null, null, null)
					},
					onPaste = {
						val clip = readClipboardText(context)
						if (clip.isNotBlank()) {
							geminiKeyText = clip
							onUpdate(null, null, clip, null, null, null)
							onPasted()
						}
					},
				)
			} else {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.horizontalScroll(rememberScrollState()),
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					listOf(
						"OpenAI" to "https://api.openai.com/v1",
						"OpenRouter" to "https://openrouter.ai/api/v1",
						"Groq" to "https://api.groq.com/openai/v1",
						"DeepSeek" to "https://api.deepseek.com/v1",
					).forEach { (label, url) ->
						val active = openAiUrlText.trimEnd('/') == url.trimEnd('/')
						Surface(
							shape = RoundedCornerShape(50),
							color = if (active) NemuriCyan else ObsidianInputBg,
							border = BorderStroke(
								1.dp,
								if (active) NemuriCyan else Color.White.copy(alpha = 0.10f),
							),
							modifier = Modifier
								.clip(RoundedCornerShape(50))
								.clickable {
									openAiUrlText = url
									onUpdate(null, null, null, url, null, null)
								},
						) {
							Text(
								text = label,
								style = MaterialTheme.typography.labelSmall,
								fontWeight = FontWeight.SemiBold,
								color = if (active) NemuriDarkInk else Color.White,
								modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
							)
						}
					}
				}

				OutlinedTextField(
					value = openAiUrlText,
					onValueChange = {
						openAiUrlText = it
						onUpdate(null, null, null, it, null, null)
					},
					label = { Text("OpenAI-Compatible Base URL") },
					singleLine = true,
					colors = obsidianTextFieldColors(),
					modifier = Modifier.fillMaxWidth(),
				)

				OutlinedTextField(
					value = openAiModelText,
					onValueChange = {
						openAiModelText = it
						onUpdate(null, null, null, null, it, null)
					},
					label = { Text("Model ID (e.g. gpt-4o-mini)") },
					singleLine = true,
					colors = obsidianTextFieldColors(),
					modifier = Modifier.fillMaxWidth(),
				)

				SecretKeyTextField(
					value = openAiKeyText,
					label = "Cloud Bearer API Key (Keystore AES-GCM)",
					showSecret = showSecret,
					onToggleShow = { showSecret = !showSecret },
					onValueChange = {
						openAiKeyText = it
						onUpdate(null, null, null, null, null, it)
					},
					onPaste = {
						val clip = readClipboardText(context)
						if (clip.isNotBlank()) {
							openAiKeyText = clip
							onUpdate(null, null, null, null, null, clip)
							onPasted()
						}
					},
				)
			}
		}
	}
}

@Composable
private fun SecretKeyTextField(
	value: String,
	label: String,
	showSecret: Boolean,
	onToggleShow: () -> Unit,
	onValueChange: (String) -> Unit,
	onPaste: () -> Unit,
) {
	OutlinedTextField(
		value = value,
		onValueChange = onValueChange,
		label = { Text(label) },
		singleLine = true,
		visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
		trailingIcon = {
			Row(verticalAlignment = Alignment.CenterVertically) {
				IconButton(onClick = onPaste) {
					Icon(
						painter = painterResource(R.drawable.ic_content_copy),
						contentDescription = "Paste API key",
						tint = NemuriCyan,
						modifier = Modifier.size(18.dp),
					)
				}
				IconButton(onClick = onToggleShow) {
					Icon(
						painter = painterResource(if (showSecret) R.drawable.ic_eye_off else R.drawable.ic_eye),
						contentDescription = "Toggle visibility",
						tint = NemuriMuted,
						modifier = Modifier.size(18.dp),
					)
				}
			}
		},
		colors = obsidianTextFieldColors(),
		modifier = Modifier.fillMaxWidth(),
	)
}

@Composable
private fun ConnectionVerifierCard(
	config: AiRouterConfig,
	onTestConnection: () -> Unit,
) {
	Surface(
		shape = RoundedCornerShape(18.dp),
		color = ObsidianCardBg,
		border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Button(
				onClick = onTestConnection,
				enabled = !config.isBusy,
				shape = RoundedCornerShape(50),
				colors = ButtonDefaults.buttonColors(
					containerColor = NemuriCyan,
					contentColor = NemuriDarkInk,
				),
				modifier = Modifier
					.fillMaxWidth()
					.height(48.dp),
			) {
				if (config.isBusy) {
					CircularProgressIndicator(
						color = NemuriDarkInk,
						strokeWidth = 2.dp,
						modifier = Modifier.size(18.dp),
					)
					Spacer(Modifier.width(8.dp))
					Text(
						text = stringResource(R.string.ai_testing_connection),
						fontWeight = FontWeight.Bold,
					)
				} else {
					Icon(
						painter = painterResource(R.drawable.ic_sparkles),
						contentDescription = null,
						modifier = Modifier.size(18.dp),
					)
					Spacer(Modifier.width(8.dp))
					Text(
						text = stringResource(R.string.ai_test_connection_button),
						fontWeight = FontWeight.Bold,
					)
				}
			}

			if (!config.lastStatusMessage.isNullOrBlank()) {
				HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
				val ok = config.lastVerifiedSuccess != false
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Icon(
						painter = painterResource(
							if (ok) R.drawable.ic_check else R.drawable.ic_close_small,
						),
						contentDescription = null,
						tint = if (ok) VerifiedEmerald else ErrorRose,
						modifier = Modifier.size(16.dp),
					)
					Text(
						text = config.lastStatusMessage,
						style = MaterialTheme.typography.bodySmall,
						color = if (ok) VerifiedEmerald else ErrorRose,
					)
				}
			}
		}
	}
}

@Composable
private fun obsidianTextFieldColors() = OutlinedTextFieldDefaults.colors(
	focusedTextColor = Color.White,
	unfocusedTextColor = Color.White,
	focusedContainerColor = ObsidianInputBg,
	unfocusedContainerColor = ObsidianInputBg,
	focusedBorderColor = NemuriCyan,
	unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
	focusedLabelColor = NemuriCyan,
	unfocusedLabelColor = NemuriMuted,
	cursorColor = NemuriCyan,
)

private fun readClipboardText(context: Context): String {
	val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
	val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return ""
	return item.coerceToText(context)?.toString()?.trim().orEmpty()
}
