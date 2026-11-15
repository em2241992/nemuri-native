package org.koitharu.kotatsu.core.ai

enum class AiBackendMode(
	val id: String,
	val shortBadge: String,
) {
	ON_DEVICE_LOCAL("ON_DEVICE_LOCAL", "On-Device"),
	OLLAMA_LAN("OLLAMA_LAN", "Ollama / LAN"),
	CLOUD_API("CLOUD_API", "Cloud API"),
	;

	companion object {
		fun fromId(raw: String?): AiBackendMode = entries.firstOrNull { it.id == raw } ?: ON_DEVICE_LOCAL
	}
}

enum class CloudAiProvider(
	val id: String,
	val label: String,
) {
	GOOGLE_GEMINI("GOOGLE_GEMINI", "Google Gemini"),
	OPENAI_COMPATIBLE("OPENAI_COMPATIBLE", "OpenAI-Compatible"),
	;

	companion object {
		fun fromId(raw: String?): CloudAiProvider = entries.firstOrNull { it.id == raw } ?: GOOGLE_GEMINI
	}
}

enum class GeminiCloudModel(
	val modelId: String,
	val displayName: String,
) {
	GEMINI_2_5_FLASH("gemini-2.5-flash", "Gemini 2.5 Flash"),
	GEMINI_2_5_FLASH_LITE("gemini-2.5-flash-lite", "Gemini 2.5 Flash-Lite"),
	GEMINI_2_5_PRO("gemini-2.5-pro", "Gemini 2.5 Pro"),
	;

	companion object {
		fun fromModelId(id: String?): GeminiCloudModel =
			entries.firstOrNull { it.modelId == id } ?: GEMINI_2_5_FLASH
	}
}

enum class OllamaEndpointPreset(
	val label: String,
	val baseUrl: String,
) {
	LOCAL_TERMUX("127.0.0.1:11434", "http://127.0.0.1:11434"),
	HOME_LAN("LAN 192.168.1.x", "http://192.168.1.100:11434"),
	EMULATOR_HOST("10.0.2.2:11434", "http://10.0.2.2:11434"),
	LM_STUDIO("LM Studio :1234", "http://127.0.0.1:1234/v1"),
}

data class AiConnectionTestResult(
	val success: Boolean,
	val latencyMs: Long,
	val activeModel: String,
	val message: String,
)

data class AiGenerationOutput(
	val text: String,
	val backendAttribution: String,
)

data class AiRouterConfig(
	val masterAiEnabled: Boolean = true,
	val backendMode: AiBackendMode = AiBackendMode.ON_DEVICE_LOCAL,
	// Backend 1: On-Device Local (.litertlm / Android AICore)
	val localModelUri: String? = null,
	val localModelFileName: String? = null,
	val isAiCoreAvailable: Boolean = false,
	val deviceHardwareSummary: String = "",
	// Backend 2: Local Loopback / LAN (Ollama)
	val ollamaBaseUrl: String = "http://127.0.0.1:11434",
	val ollamaModelId: String = "llama3.2:3b",
	val ollamaApiKey: String = "",
	val ollamaAvailableModels: List<String> = emptyList(),
	// Backend 3: Cloud API (Google Gemini + OpenAI-Compatible)
	val cloudProvider: CloudAiProvider = CloudAiProvider.GOOGLE_GEMINI,
	val geminiModelId: String = GeminiCloudModel.GEMINI_2_5_FLASH.modelId,
	val geminiApiKey: String = "",
	val openAiBaseUrl: String = "https://api.openai.com/v1",
	val openAiModelId: String = "gpt-4o-mini",
	val openAiApiKey: String = "",
	// Live UI status
	val isBusy: Boolean = false,
	val lastStatusMessage: String? = null,
	val lastVerifiedSuccess: Boolean? = null,
) {
	fun activeAttributionBadge(): String = when (backendMode) {
		AiBackendMode.ON_DEVICE_LOCAL -> when {
			!localModelFileName.isNullOrBlank() -> "On-Device • $localModelFileName"
			isAiCoreAvailable -> "On-Device • Gemini Nano (AICore)"
			else -> "On-Device • Reading Trail"
		}
		AiBackendMode.OLLAMA_LAN -> "Ollama • ${ollamaModelId.ifBlank { "local" }}"
		AiBackendMode.CLOUD_API -> when (cloudProvider) {
			CloudAiProvider.GOOGLE_GEMINI -> GeminiCloudModel.fromModelId(geminiModelId).displayName
			CloudAiProvider.OPENAI_COMPATIBLE -> "Cloud • ${openAiModelId.ifBlank { "OpenAI" }}"
		}
	}
}
