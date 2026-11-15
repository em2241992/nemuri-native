package org.koitharu.kotatsu.core.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.os.HardwareAcceleratorProbe
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Exception indicating that no neural LLM weights are currently loaded for [AiBackendMode.ON_DEVICE_LOCAL],
 * signaling callers like [org.koitharu.kotatsu.details.domain.WhereWasIRecapUseCase] to produce a
 * deterministic, 100% offline spoiler-locked extractive summary without crashing.
 */
class LocalExtractiveFallbackException(message: String) : IllegalStateException(message)

/**
 * 3-Backend Pluggable AI Router for Nemuri:
 * 1. [AiBackendMode.ON_DEVICE_LOCAL]: Android SAF `.litertlm` / `.task` file support + runtime
 *    Android AICore / MediaPipe GenAI reflection bridge + deterministic offline fallback.
 * 2. [AiBackendMode.OLLAMA_LAN]: Real [OkHttpClient] loopback (`127.0.0.1:11434`) & RFC1918 LAN
 *    integration (`GET /api/tags`, `/v1/models`, `POST /api/generate`, `POST /v1/chat/completions`).
 * 3. [AiBackendMode.CLOUD_API]: Real HTTPS [OkHttpClient] client for Google Gemini REST API
 *    (`v1beta/models/{model}:generateContent`, default `gemini-2.5-flash`) and OpenAI-compatible
 *    `/v1/chat/completions` endpoints, backed by [AiCredentialStore] hardware Keystore encryption.
 */
@Singleton
class PluggableAiRouter @Inject constructor(
	@ApplicationContext private val context: Context,
	private val settings: AppSettings,
	private val credentialStore: AiCredentialStore,
	@BaseHttpClient private val baseHttpClientProvider: Provider<OkHttpClient>,
) {

	private val llmHttpClient: OkHttpClient by lazy {
		baseHttpClientProvider.get().newBuilder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(45, TimeUnit.SECONDS)
			.writeTimeout(15, TimeUnit.SECONDS)
			.dns(Dns.SYSTEM)
			.cache(null)
			.build()
	}

	private val _configFlow = MutableStateFlow(loadPersistedConfig())
	val configFlow: StateFlow<AiRouterConfig> = _configFlow.asStateFlow()

	fun refreshConfig() {
		_configFlow.update { prev ->
			loadPersistedConfig().copy(
				isBusy = prev.isBusy,
				lastStatusMessage = prev.lastStatusMessage,
				lastVerifiedSuccess = prev.lastVerifiedSuccess,
			)
		}
	}

	fun setMasterAiEnabled(enabled: Boolean) {
		settings.isAiMasterEnabled = enabled
		_configFlow.update { it.copy(masterAiEnabled = enabled) }
	}

	fun setBackendMode(mode: AiBackendMode) {
		settings.aiBackendMode = mode.id
		_configFlow.update {
			it.copy(
				backendMode = mode,
				lastStatusMessage = null,
				lastVerifiedSuccess = null,
			)
		}
	}

	fun setComputeDelegate(delegate: AiComputeDelegate) {
		settings.aiComputeDelegate = delegate.id
		val caps = HardwareAcceleratorProbe.inspect(context)
		val chain = HardwareAcceleratorProbe.resolveAiDelegateChain(
			requested = delegate,
			hasExposedNpu = caps.hasDedicatedExposedNpu,
			hasGpuCompute = caps.isVulkanComputeAvailable,
		)
		val activeDelegate = chain.firstOrNull() ?: AiComputeDelegate.CPU
		val fallbackHint = if (delegate == AiComputeDelegate.NPU && !caps.hasDedicatedExposedNpu) {
			"NPU runtime unavailable on ${caps.socModel.ifBlank { Build.MODEL }} — falling back to ${activeDelegate.shortBadge}"
		} else {
			"Compute delegate: ${delegate.shortBadge} (active: ${activeDelegate.shortBadge})"
		}
		_configFlow.update {
			it.copy(
				computeDelegate = delegate,
				resolvedActiveDelegate = activeDelegate,
				hasDedicatedNpu = caps.hasDedicatedExposedNpu,
				deviceHardwareSummary = caps.summaryLine(),
				lastStatusMessage = fallbackHint,
			)
		}
	}

	/**
	 * Persists a read-only SAF grant for a user-selected `.litertlm`, `.task`, or `.gguf` model file.
	 */
	fun setLocalModelSafUri(uri: Uri?): String {
		if (uri == null) {
			settings.aiLocalModelUri = null
			settings.aiLocalModelName = null
			_configFlow.update {
				it.copy(
					localModelUri = null,
					localModelFileName = null,
					lastStatusMessage = "Cleared local model weights selection",
				)
			}
			return "Cleared local model weights selection"
		}
		runCatching {
			context.contentResolver.takePersistableUriPermission(
				uri,
				Intent.FLAG_GRANT_READ_URI_PERMISSION,
			)
		}
		val doc = DocumentFile.fromSingleUri(context, uri)
		val resolvedName = doc?.name?.takeIf { it.isNotBlank() }
			?: uri.lastPathSegment?.substringAfterLast('/')
			?: "local_model.litertlm"
		settings.aiLocalModelUri = uri.toString()
		settings.aiLocalModelName = resolvedName
		val format = LocalModelFormat.fromFileName(resolvedName)
		val status = "Selected ${format.displayLabel}: $resolvedName"
		_configFlow.update {
			it.copy(
				localModelUri = uri.toString(),
				localModelFileName = resolvedName,
				lastStatusMessage = status,
				lastVerifiedSuccess = true,
			)
		}
		return status
	}

	fun updateOllamaSettings(
		baseUrl: String? = null,
		modelId: String? = null,
		apiKey: String? = null,
	) {
		baseUrl?.let { settings.aiOllamaBaseUrl = it }
		modelId?.let { settings.aiOllamaModelId = it }
		apiKey?.let { credentialStore.ollamaApiKey = it }
		_configFlow.update { prev ->
			prev.copy(
				ollamaBaseUrl = baseUrl?.trim()?.ifBlank { prev.ollamaBaseUrl } ?: prev.ollamaBaseUrl,
				ollamaModelId = modelId?.trim()?.ifBlank { prev.ollamaModelId } ?: prev.ollamaModelId,
				ollamaApiKey = apiKey?.trim() ?: prev.ollamaApiKey,
				lastVerifiedSuccess = null,
			)
		}
	}

	fun updateCloudSettings(
		provider: CloudAiProvider? = null,
		geminiModelId: String? = null,
		geminiApiKey: String? = null,
		openAiBaseUrl: String? = null,
		openAiModelId: String? = null,
		openAiApiKey: String? = null,
	) {
		provider?.let { settings.aiCloudProvider = it.id }
		geminiModelId?.let { settings.aiGeminiModelId = it }
		geminiApiKey?.let { credentialStore.geminiApiKey = it }
		openAiBaseUrl?.let { settings.aiOpenAiBaseUrl = it }
		openAiModelId?.let { settings.aiOpenAiModelId = it }
		openAiApiKey?.let { credentialStore.openAiApiKey = it }
		_configFlow.update { prev ->
			prev.copy(
				cloudProvider = provider ?: prev.cloudProvider,
				geminiModelId = geminiModelId?.trim()?.ifBlank { prev.geminiModelId } ?: prev.geminiModelId,
				geminiApiKey = geminiApiKey?.trim() ?: prev.geminiApiKey,
				openAiBaseUrl = openAiBaseUrl?.trim()?.ifBlank { prev.openAiBaseUrl } ?: prev.openAiBaseUrl,
				openAiModelId = openAiModelId?.trim()?.ifBlank { prev.openAiModelId } ?: prev.openAiModelId,
				openAiApiKey = openAiApiKey?.trim() ?: prev.openAiApiKey,
				lastVerifiedSuccess = null,
			)
		}
	}

	/**
	 * Queries a live Ollama (`GET /api/tags`) or OpenAI-compatible (`GET /v1/models`) endpoint
	 * over [OkHttpClient] and populates `ollamaAvailableModels`.
	 */
	suspend fun fetchOllamaModels(): Result<List<String>> = withContext(Dispatchers.IO) {
		val cfg = _configFlow.value
		_configFlow.update { it.copy(isBusy = true, lastStatusMessage = null) }
		val startMs = SystemClock.elapsedRealtime()
		val result = runCatchingCancellable {
			val models = discoverRemoteModels(cfg.ollamaBaseUrl, cfg.ollamaApiKey)
			if (models.isEmpty()) {
				error("Connected, but no models are installed on ${cfg.ollamaBaseUrl} (run `ollama pull llama3.2:3b`)")
			}
			models
		}
		val elapsedMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
		result.fold(
			onSuccess = { models ->
				val chosen = if (cfg.ollamaModelId in models) cfg.ollamaModelId else models.first()
				settings.aiOllamaCachedModels = models.toSet()
				settings.aiOllamaModelId = chosen
				val msg = "Found ${models.size} model(s) on LAN/loopback ($elapsedMs ms)"
				_configFlow.update {
					it.copy(
						isBusy = false,
						ollamaAvailableModels = models,
						ollamaModelId = chosen,
						lastStatusMessage = msg,
						lastVerifiedSuccess = true,
					)
				}
				Result.success(models)
			},
			onFailure = { err ->
				val msg = "Model fetch failed: ${err.message ?: err.javaClass.simpleName}"
				_configFlow.update {
					it.copy(
						isBusy = false,
						lastStatusMessage = msg,
						lastVerifiedSuccess = false,
					)
				}
				Result.failure(err)
			},
		)
	}

	/**
	 * Verifies the currently selected backend, returning latency (`ms`) + active model name
	 * for the bottom capsule toast.
	 */
	suspend fun testConnection(): AiConnectionTestResult = withContext(Dispatchers.IO) {
		val cfg = _configFlow.value
		_configFlow.update { it.copy(isBusy = true, lastStatusMessage = null) }
		val startMs = SystemClock.elapsedRealtime()

		val testResult = when (cfg.backendMode) {
			AiBackendMode.ON_DEVICE_LOCAL -> verifyOnDeviceBackend(cfg, startMs)
			AiBackendMode.OLLAMA_LAN -> verifyOllamaBackend(cfg, startMs)
			AiBackendMode.CLOUD_API -> verifyCloudBackend(cfg, startMs)
		}

		_configFlow.update {
			it.copy(
				isBusy = false,
				lastStatusMessage = testResult.message,
				lastVerifiedSuccess = testResult.success,
			)
		}
		testResult
	}

	/**
	 * Generates spoiler-safe text on the active LLM backend.
	 */
	suspend fun generateText(
		systemInstruction: String,
		userPrompt: String,
		temperature: Float = 0.25f,
		maxOutputTokens: Int = 320,
	): Result<AiGenerationOutput> = withContext(Dispatchers.IO) {
		val cfg = _configFlow.value
		if (!cfg.masterAiEnabled) {
			return@withContext Result.failure(IllegalStateException("Generative AI features are turned OFF."))
		}

		runCatchingCancellable {
			when (cfg.backendMode) {
				AiBackendMode.ON_DEVICE_LOCAL -> generateViaOnDevice(
					cfg = cfg,
					systemInstruction = systemInstruction,
					userPrompt = userPrompt,
					temperature = temperature,
					maxTokens = maxOutputTokens,
				)

				AiBackendMode.OLLAMA_LAN -> {
					val text = generateViaOllamaOrLan(
						baseUrl = cfg.ollamaBaseUrl,
						modelId = cfg.ollamaModelId,
						apiKey = cfg.ollamaApiKey,
						systemInstruction = systemInstruction,
						userPrompt = userPrompt,
						temperature = temperature,
						maxTokens = maxOutputTokens,
					)
					AiGenerationOutput(
						text = text,
						backendAttribution = "Ollama • ${cfg.ollamaModelId}",
					)
				}

				AiBackendMode.CLOUD_API -> when (cfg.cloudProvider) {
					CloudAiProvider.GOOGLE_GEMINI -> {
						val model = cfg.geminiModelId.ifBlank { GeminiCloudModel.GEMINI_2_5_FLASH.modelId }
						val text = generateViaGeminiRest(
							apiKey = cfg.geminiApiKey,
							modelId = model,
							systemInstruction = systemInstruction,
							userPrompt = userPrompt,
							temperature = temperature,
							maxTokens = maxOutputTokens,
						)
						AiGenerationOutput(
							text = text,
							backendAttribution = GeminiCloudModel.fromModelId(model).displayName,
						)
					}

					CloudAiProvider.OPENAI_COMPATIBLE -> {
						val model = cfg.openAiModelId.ifBlank { "gpt-4o-mini" }
						val text = generateViaOpenAiCompatible(
							baseUrl = cfg.openAiBaseUrl,
							modelId = model,
							apiKey = cfg.openAiApiKey,
							systemInstruction = systemInstruction,
							userPrompt = userPrompt,
							temperature = temperature,
							maxTokens = maxOutputTokens,
						)
						AiGenerationOutput(
							text = text,
							backendAttribution = "Cloud • $model",
						)
					}
				}
			}
		}
	}

	private fun verifyOnDeviceBackend(
		cfg: AiRouterConfig,
		startMs: Long,
	): AiConnectionTestResult {
		val caps = HardwareAcceleratorProbe.inspect(context)
		val delegateChain = HardwareAcceleratorProbe.resolveAiDelegateChain(
			requested = cfg.computeDelegate,
			hasExposedNpu = caps.hasDedicatedExposedNpu,
			hasGpuCompute = caps.isVulkanComputeAvailable,
		)
		val primaryDelegate = delegateChain.firstOrNull() ?: AiComputeDelegate.CPU
		val cascadeLabel = delegateChain.joinToString(" → ") { it.shortBadge }
		val uriStr = cfg.localModelUri
		if (!uriStr.isNullOrBlank()) {
			val uri = runCatching { Uri.parse(uriStr) }.getOrNull()
			val pfd = uri?.let { runCatching { context.contentResolver.openFileDescriptor(it, "r") }.getOrNull() }
			if (pfd != null) {
				val sizeMb = pfd.use { fd -> (fd.statSize.coerceAtLeast(0L)) / (1024L * 1024L) }
				val modelLabel = cfg.localModelFileName ?: "local.litertlm"
				val format = LocalModelFormat.fromFileName(modelLabel)
				if (format == LocalModelFormat.GGUF && uri != null) {
					val validGguf = verifyGgufHeaderMagic(uri)
					val latency = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
					return if (!validGguf) {
						AiConnectionTestResult(
							success = false,
							latencyMs = latency,
							activeModel = modelLabel,
							message = "Invalid GGUF header in $modelLabel — verify file integrity",
						)
					} else {
						AiConnectionTestResult(
							success = true,
							latencyMs = latency,
							activeModel = "$modelLabel (${primaryDelegate.shortBadge})",
							message = "Verified GGUF $modelLabel (${sizeMb} MB) • Cascade [$cascadeLabel] ready ($latency ms)",
						)
					}
				}
				val runtimePresent = isMediaPipeLlmRuntimePresent()
				val latency = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
				val detail = if (runtimePresent) {
					"Verified $modelLabel (${sizeMb} MB) • MediaPipe [$cascadeLabel] active ($latency ms)"
				} else {
					"SAF verified $modelLabel (${sizeMb} MB) • Cascade [$cascadeLabel] + Reading Trail ready ($latency ms)"
				}
				return AiConnectionTestResult(
					success = true,
					latencyMs = latency,
					activeModel = "$modelLabel (${primaryDelegate.shortBadge})",
					message = detail,
				)
			} else {
				val latency = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
				return AiConnectionTestResult(
					success = false,
					latencyMs = latency,
					activeModel = "Unreadable SAF URI",
					message = "Selected model file permission expired — please re-select via SAF picker",
				)
			}
		}

		val latency = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
		return if (caps.hasDedicatedExposedNpu && caps.isAiCoreAvailable) {
			AiConnectionTestResult(
				success = true,
				latencyMs = latency,
				activeModel = "AICore (NPU) / Reading Trail",
				message = "Android AICore NPU detected on ${Build.MODEL} • Cascade [$cascadeLabel] ready ($latency ms)",
			)
		} else {
			AiConnectionTestResult(
				success = true,
				latencyMs = latency,
				activeModel = "${primaryDelegate.shortBadge} • Reading Trail",
				message = "Delegate cascade [$cascadeLabel] ready ($latency ms) • Pick .litertlm / .task / .gguf or use Local/Cloud",
			)
		}
	}

	private fun verifyGgufHeaderMagic(uri: Uri): Boolean = runCatching {
		context.contentResolver.openInputStream(uri)?.use { stream ->
			val magic = ByteArray(4)
			val read = stream.read(magic)
			read == 4 &&
				magic[0] == 'G'.code.toByte() &&
				magic[1] == 'G'.code.toByte() &&
				magic[2] == 'U'.code.toByte() &&
				magic[3] == 'F'.code.toByte()
		} ?: false
	}.getOrDefault(false)

	private fun verifyOllamaBackend(
		cfg: AiRouterConfig,
		startMs: Long,
	): AiConnectionTestResult {
		return runCatching {
			val models = discoverRemoteModels(cfg.ollamaBaseUrl, cfg.ollamaApiKey)
			val latency = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
			val active = when {
				cfg.ollamaModelId in models -> cfg.ollamaModelId
				models.isNotEmpty() -> models.first().also { settings.aiOllamaModelId = it }
				else -> cfg.ollamaModelId
			}
			if (models.isNotEmpty()) {
				settings.aiOllamaCachedModels = models.toSet()
				_configFlow.update { it.copy(ollamaAvailableModels = models, ollamaModelId = active) }
				AiConnectionTestResult(
					success = true,
					latencyMs = latency,
					activeModel = active,
					message = "Connected to Ollama • $active ($latency ms)",
				)
			} else {
				AiConnectionTestResult(
					success = false,
					latencyMs = latency,
					activeModel = active,
					message = "Ollama reachable ($latency ms), but 0 models installed — run `ollama pull llama3.2:3b`",
				)
			}
		}.getOrElse { err ->
			val latency = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
			AiConnectionTestResult(
				success = false,
				latencyMs = latency,
				activeModel = cfg.ollamaModelId,
				message = "Ollama unreachable at ${cfg.ollamaBaseUrl}: ${err.message ?: err.javaClass.simpleName}",
			)
		}
	}

	private fun verifyCloudBackend(
		cfg: AiRouterConfig,
		startMs: Long,
	): AiConnectionTestResult {
		return runCatching {
			when (cfg.cloudProvider) {
				CloudAiProvider.GOOGLE_GEMINI -> {
					val model = cfg.geminiModelId.ifBlank { GeminiCloudModel.GEMINI_2_5_FLASH.modelId }
					generateViaGeminiRest(
						apiKey = cfg.geminiApiKey,
						modelId = model,
						systemInstruction = "You are a connectivity check responder.",
						userPrompt = "Reply with the single word OK.",
						temperature = 0.0f,
						maxTokens = 12,
					)
					val latency = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
					AiConnectionTestResult(
						success = true,
						latencyMs = latency,
						activeModel = model,
						message = "Verified Google Gemini • $model ($latency ms)",
					)
				}

				CloudAiProvider.OPENAI_COMPATIBLE -> {
					val model = cfg.openAiModelId.ifBlank { "gpt-4o-mini" }
					generateViaOpenAiCompatible(
						baseUrl = cfg.openAiBaseUrl,
						modelId = model,
						apiKey = cfg.openAiApiKey,
						systemInstruction = "You are a connectivity check responder.",
						userPrompt = "Reply with the single word OK.",
						temperature = 0.0f,
						maxTokens = 12,
					)
					val latency = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
					AiConnectionTestResult(
						success = true,
						latencyMs = latency,
						activeModel = model,
						message = "Verified Cloud API • $model ($latency ms)",
					)
				}
			}
		}.getOrElse { err ->
			val latency = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
			val model = if (cfg.cloudProvider == CloudAiProvider.GOOGLE_GEMINI) {
				cfg.geminiModelId
			} else {
				cfg.openAiModelId
			}
			AiConnectionTestResult(
				success = false,
				latencyMs = latency,
				activeModel = model,
				message = "Cloud verification failed: ${err.message ?: err.javaClass.simpleName}",
			)
		}
	}

	private fun generateViaOnDevice(
		cfg: AiRouterConfig,
		systemInstruction: String,
		userPrompt: String,
		temperature: Float,
		maxTokens: Int,
	): AiGenerationOutput {
		val uriStr = cfg.localModelUri
		val format = LocalModelFormat.fromFileName(cfg.localModelFileName)
		if (!uriStr.isNullOrBlank() && format != LocalModelFormat.GGUF && isMediaPipeLlmRuntimePresent()) {
			val uri = Uri.parse(uriStr)
			val stagedPath = stageSafModelForMediaPipeIfNeeded(uri, cfg.localModelFileName)
			if (stagedPath != null) {
				val caps = HardwareAcceleratorProbe.inspect(context)
				val delegateChain = HardwareAcceleratorProbe.resolveAiDelegateChain(
					requested = cfg.computeDelegate,
					hasExposedNpu = caps.hasDedicatedExposedNpu,
					hasGpuCompute = caps.isVulkanComputeAvailable,
				)
				for (candidateDelegate in delegateChain) {
					val generated = invokeMediaPipeLlmReflection(
						modelPath = stagedPath,
						prompt = "$systemInstruction\n\n$userPrompt",
						temperature = temperature,
						maxTokens = maxTokens,
						preferredDelegate = candidateDelegate,
					)
					if (!generated.isNullOrBlank()) {
						_configFlow.update { it.copy(resolvedActiveDelegate = candidateDelegate) }
						return AiGenerationOutput(
							text = generated.trim(),
							backendAttribution = "On-Device (${candidateDelegate.shortBadge}) • ${cfg.localModelFileName ?: "LiteRT-LM"}",
						)
					}
				}
			}
		}
		throw LocalExtractiveFallbackException(
			"Using deterministic offline spoiler-locked Reading Trail.",
		)
	}

	private fun discoverRemoteModels(rawBaseUrl: String, apiKey: String): List<String> {
		val cleanBase = normalizeHttpBaseUrl(rawBaseUrl)
		val origin = cleanBase.removeSuffix("/v1").removeSuffix("/api")

		// 1. Try native Ollama GET /api/tags first (unless explicit /v1 suffix was configured)
		if (!cleanBase.endsWith("/v1")) {
			runCatching {
				val reqBuilder = Request.Builder()
					.url("$origin/api/tags")
					.get()
				if (apiKey.isNotBlank()) {
					reqBuilder.header("Authorization", "Bearer $apiKey")
				}
				llmHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
					if (resp.isSuccessful) {
						val body = resp.body.string()
						val arr = JSONObject(body).optJSONArray("models") ?: JSONArray()
						val names = buildList {
							for (i in 0 until arr.length()) {
								val name = arr.optJSONObject(i)?.optString("name")?.trim().orEmpty()
								if (name.isNotEmpty()) add(name)
							}
						}
						return names
					}
				}
			}
		}

		// 2. Fallback to OpenAI-compatible GET /v1/models
		val modelsUrl = if (cleanBase.endsWith("/v1")) "$cleanBase/models" else "$origin/v1/models"
		val reqBuilder = Request.Builder()
			.url(modelsUrl)
			.get()
		if (apiKey.isNotBlank()) {
			reqBuilder.header("Authorization", "Bearer $apiKey")
		}
		llmHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
			if (!resp.isSuccessful) {
				error("HTTP ${resp.code} from $modelsUrl")
			}
			val body = resp.body.string()
			val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
			return buildList {
				for (i in 0 until data.length()) {
					val id = data.optJSONObject(i)?.optString("id")?.trim().orEmpty()
					if (id.isNotEmpty()) add(id)
				}
			}
		}
	}

	private fun generateViaOllamaOrLan(
		baseUrl: String,
		modelId: String,
		apiKey: String,
		systemInstruction: String,
		userPrompt: String,
		temperature: Float,
		maxTokens: Int,
	): String {
		val cleanBase = normalizeHttpBaseUrl(baseUrl)
		val resolvedModel = modelId.trim().ifBlank { error("Ollama model ID cannot be empty.") }
		if (cleanBase.endsWith("/v1")) {
			return generateViaOpenAiCompatible(
				baseUrl = cleanBase,
				modelId = resolvedModel,
				apiKey = apiKey,
				systemInstruction = systemInstruction,
				userPrompt = userPrompt,
				temperature = temperature,
				maxTokens = maxTokens,
			)
		}

		val origin = cleanBase.removeSuffix("/api")
		val payload = JSONObject().apply {
			put("model", resolvedModel)
			put("system", systemInstruction)
			put("prompt", userPrompt)
			put("stream", false)
			put(
				"options",
				JSONObject().apply {
					put("temperature", temperature.toDouble())
					put("num_predict", maxTokens)
				},
			)
		}
		val requestBuilder = Request.Builder()
			.url("$origin/api/generate")
			.post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
		if (apiKey.isNotBlank()) {
			requestBuilder.header("Authorization", "Bearer $apiKey")
		}

		llmHttpClient.newCall(requestBuilder.build()).execute().use { response ->
			if (response.code == 404) {
				// Server may be an OpenAI-compatible LAN endpoint (LM Studio / llama.cpp) without /v1 in URL
				return generateViaOpenAiCompatible(
					baseUrl = "$origin/v1",
					modelId = resolvedModel,
					apiKey = apiKey,
					systemInstruction = systemInstruction,
					userPrompt = userPrompt,
					temperature = temperature,
					maxTokens = maxTokens,
				)
			}
			val rawBody = response.body.string()
			if (!response.isSuccessful) {
				val serverErr = runCatching { JSONObject(rawBody).optString("error") }.getOrDefault("")
				error(
					if (serverErr.isNotBlank()) "Ollama HTTP ${response.code}: $serverErr"
					else "Ollama HTTP ${response.code}",
				)
			}
			val reply = JSONObject(rawBody).optString("response").trim()
			if (reply.isBlank()) {
				error("Ollama returned an empty response for model '$resolvedModel'.")
			}
			return reply
		}
	}

	private fun generateViaOpenAiCompatible(
		baseUrl: String,
		modelId: String,
		apiKey: String,
		systemInstruction: String,
		userPrompt: String,
		temperature: Float,
		maxTokens: Int,
	): String {
		val cleanBase = normalizeHttpBaseUrl(baseUrl)
		val endpoint = if (cleanBase.endsWith("/chat/completions")) {
			cleanBase
		} else if (cleanBase.endsWith("/v1")) {
			"$cleanBase/chat/completions"
		} else {
			"$cleanBase/v1/chat/completions"
		}
		val messages = JSONArray().apply {
			put(
				JSONObject().apply {
					put("role", "system")
					put("content", systemInstruction)
				},
			)
			put(
				JSONObject().apply {
					put("role", "user")
					put("content", userPrompt)
				},
			)
		}
		val payload = JSONObject().apply {
			put("model", modelId.trim())
			put("messages", messages)
			put("temperature", temperature.toDouble())
			put("max_tokens", maxTokens)
			put("stream", false)
		}
		val reqBuilder = Request.Builder()
			.url(endpoint)
			.header("Content-Type", "application/json")
			.post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
		if (apiKey.isNotBlank()) {
			reqBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
		}

		llmHttpClient.newCall(reqBuilder.build()).execute().use { response ->
			val rawBody = response.body.string()
			if (!response.isSuccessful) {
				val apiErr = runCatching {
					JSONObject(rawBody).optJSONObject("error")?.optString("message").orEmpty()
				}.getOrDefault("")
				error(
					if (apiErr.isNotBlank()) "HTTP ${response.code}: $apiErr"
					else "HTTP ${response.code} from $endpoint",
				)
			}
			val choices = JSONObject(rawBody).optJSONArray("choices")
			val content = choices
				?.optJSONObject(0)
				?.optJSONObject("message")
				?.optString("content")
				?.trim()
				.orEmpty()
			if (content.isBlank()) {
				error("OpenAI-compatible endpoint returned empty message content.")
			}
			return content
		}
	}

	private fun generateViaGeminiRest(
		apiKey: String,
		modelId: String,
		systemInstruction: String,
		userPrompt: String,
		temperature: Float,
		maxTokens: Int,
	): String {
		val trimmedKey = apiKey.trim()
		if (trimmedKey.isBlank()) {
			error("Google Gemini API key is empty — add your key in AI Engine & LLM Router settings.")
		}
		val cleanModel = modelId.trim().removePrefix("models/")
		val url = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent"

		val payload = JSONObject().apply {
			put(
				"systemInstruction",
				JSONObject().apply {
					put(
						"parts",
						JSONArray().put(JSONObject().put("text", systemInstruction)),
					)
				},
			)
			put(
				"contents",
				JSONArray().put(
					JSONObject().apply {
						put("role", "user")
						put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
					},
				),
			)
			put(
				"generationConfig",
				JSONObject().apply {
					put("temperature", temperature.toDouble())
					put("maxOutputTokens", maxTokens)
				},
			)
		}

		val request = Request.Builder()
			.url(url)
			.header("x-goog-api-key", trimmedKey)
			.header("Content-Type", "application/json")
			.post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
			.build()

		llmHttpClient.newCall(request).execute().use { response ->
			val rawBody = response.body.string()
			if (!response.isSuccessful) {
				val geminiErr = runCatching {
					JSONObject(rawBody).optJSONObject("error")?.optString("message").orEmpty()
				}.getOrDefault("")
				error(
					if (geminiErr.isNotBlank()) "Gemini HTTP ${response.code}: $geminiErr"
					else "Gemini HTTP ${response.code}",
				)
			}
			val parts = JSONObject(rawBody)
				.optJSONArray("candidates")
				?.optJSONObject(0)
				?.optJSONObject("content")
				?.optJSONArray("parts")
			val sb = StringBuilder()
			if (parts != null) {
				for (i in 0 until parts.length()) {
					val piece = parts.optJSONObject(i)?.optString("text").orEmpty()
					if (piece.isNotEmpty()) sb.append(piece)
				}
			}
			val text = sb.toString().trim()
			if (text.isBlank()) {
				error("Gemini returned no candidate text.")
			}
			return text
		}
	}

	private fun loadPersistedConfig(): AiRouterConfig {
		val caps = HardwareAcceleratorProbe.inspect(context)
		val requestedDelegate = AiComputeDelegate.fromId(settings.aiComputeDelegate)
		val delegateChain = HardwareAcceleratorProbe.resolveAiDelegateChain(
			requested = requestedDelegate,
			hasExposedNpu = caps.hasDedicatedExposedNpu,
			hasGpuCompute = caps.isVulkanComputeAvailable,
		)
		val activeDelegate = delegateChain.firstOrNull() ?: AiComputeDelegate.CPU
		return AiRouterConfig(
			masterAiEnabled = settings.isAiMasterEnabled,
			backendMode = AiBackendMode.fromId(settings.aiBackendMode),
			computeDelegate = requestedDelegate,
			resolvedActiveDelegate = activeDelegate,
			localModelUri = settings.aiLocalModelUri,
			localModelFileName = settings.aiLocalModelName,
			isAiCoreAvailable = caps.isAiCoreAvailable,
			hasDedicatedNpu = caps.hasDedicatedExposedNpu,
			deviceHardwareSummary = caps.summaryLine(),
			ollamaBaseUrl = settings.aiOllamaBaseUrl,
			ollamaModelId = settings.aiOllamaModelId,
			ollamaApiKey = credentialStore.ollamaApiKey,
			ollamaAvailableModels = settings.aiOllamaCachedModels.sorted(),
			cloudProvider = CloudAiProvider.fromId(settings.aiCloudProvider),
			geminiModelId = settings.aiGeminiModelId,
			geminiApiKey = credentialStore.geminiApiKey,
			openAiBaseUrl = settings.aiOpenAiBaseUrl,
			openAiModelId = settings.aiOpenAiModelId,
			openAiApiKey = credentialStore.openAiApiKey,
		)
	}

	private fun isMediaPipeLlmRuntimePresent(): Boolean = runCatching {
		Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
		true
	}.getOrDefault(false)

	private fun stageSafModelForMediaPipeIfNeeded(uri: Uri, fileName: String?): String? = runCatching {
		val safeName = (fileName ?: "model.litertlm").replace(Regex("[^A-Za-z0-9._-]"), "_")
		val targetDir = File(context.cacheDir, "litertlm_models").apply { mkdirs() }
		val targetFile = File(targetDir, safeName)
		val docSize = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
		if (!targetFile.exists() || (docSize > 0L && targetFile.length() != docSize)) {
			context.contentResolver.openInputStream(uri)?.use { input ->
				targetFile.outputStream().use { output -> input.copyTo(output) }
			} ?: return@runCatching null
		}
		targetFile.absolutePath
	}.getOrNull()

	private fun invokeMediaPipeLlmReflection(
		modelPath: String,
		prompt: String,
		temperature: Float,
		maxTokens: Int,
		preferredDelegate: AiComputeDelegate,
	): String? = runCatching {
		val inferenceClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
		val optionsClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions")
		val builder = optionsClass.getMethod("builder").invoke(null)
		val builderClass = builder.javaClass
		builderClass.getMethod("setModelPath", String::class.java).invoke(builder, modelPath)
		runCatching { builderClass.getMethod("setMaxTokens", Int::class.javaPrimitiveType).invoke(builder, maxTokens) }
		runCatching { builderClass.getMethod("setTemperature", Float::class.javaPrimitiveType).invoke(builder, temperature) }
		runCatching {
			val backendEnumClass = Class.forName(
				"com.google.mediapipe.tasks.genai.llminference.LlmInference\$Backend",
			)
			val enumName = when (preferredDelegate) {
				AiComputeDelegate.NPU -> "NPU"
				AiComputeDelegate.GPU -> "GPU"
				AiComputeDelegate.CPU -> "CPU"
				AiComputeDelegate.AUTO -> "DEFAULT"
			}
			val enumValue = backendEnumClass.enumConstants?.firstOrNull { (it as Enum<*>).name == enumName }
			if (enumValue != null) {
				builderClass.getMethod("setPreferredBackend", backendEnumClass).invoke(builder, enumValue)
			}
		}
		val options = builderClass.getMethod("build").invoke(builder)
		val createMethod = inferenceClass.getMethod("createFromOptions", Context::class.java, optionsClass)
		val instance = createMethod.invoke(null, context, options) as AutoCloseable
		instance.use {
			inferenceClass.getMethod("generateResponse", String::class.java).invoke(it, prompt) as? String
		}
	}.getOrNull()

	private fun normalizeHttpBaseUrl(raw: String): String {
		val trimmed = raw.trim().trimEnd('/')
		if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
			return trimmed
		}
		return "http://$trimmed"
	}

	companion object {
		private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
	}
}
