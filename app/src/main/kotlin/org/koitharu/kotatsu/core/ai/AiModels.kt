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

enum class AiComputeDelegate(
	val id: String,
	val label: String,
	val shortBadge: String,
) {
	AUTO("AUTO", "Auto (NPU → GPU → CPU)", "Auto"),
	GPU("GPU", "GPU (Vulkan / OpenCL)", "GPU"),
	CPU("CPU", "CPU (Multi-Core)", "CPU"),
	NPU("NPU", "NPU (AICore / Hexagon)", "NPU"),
	;

	companion object {
		fun fromId(raw: String?): AiComputeDelegate = entries.firstOrNull { it.id == raw } ?: AUTO
	}
}

enum class LocalModelFormat(
	val displayLabel: String,
) {
	NONE("None"),
	LITERT_LM("LiteRT-LM (.litertlm)"),
	MEDIAPIPE_TASK("MediaPipe (.task)"),
	GGUF("GGUF (.gguf)"),
	OTHER("Local Weights"),
	;

	companion object {
		fun fromFileName(fileName: String?): LocalModelFormat {
			val lower = fileName?.trim()?.lowercase().orEmpty()
			return when {
				lower.isEmpty() -> NONE
				lower.endsWith(".litertlm") -> LITERT_LM
				lower.endsWith(".task") || lower.endsWith(".bin") -> MEDIAPIPE_TASK
				lower.endsWith(".gguf") -> GGUF
				else -> OTHER
			}
		}
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
	LOCAL_TERMUX("Ollama :11434", "http://127.0.0.1:11434"),
	LLAMA_CPP_LOCAL("llama.cpp :8080", "http://127.0.0.1:8080/v1"),
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
	// Backend 1: On-Device Local (.litertlm / .task / .gguf / Android AICore)
	val computeDelegate: AiComputeDelegate = AiComputeDelegate.AUTO,
	val resolvedActiveDelegate: AiComputeDelegate = AiComputeDelegate.GPU,
	val localModelUri: String? = null,
	val localModelFileName: String? = null,
	val isAiCoreAvailable: Boolean = false,
	val hasDedicatedNpu: Boolean = false,
	val deviceHardwareSummary: String = "",
	// Backend 2: Local Loopback / LAN (Ollama / llama.cpp)
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
	val localModelFormat: LocalModelFormat
		get() = LocalModelFormat.fromFileName(localModelFileName)

	fun activeAttributionBadge(): String = when (backendMode) {
		AiBackendMode.ON_DEVICE_LOCAL -> when {
			!localModelFileName.isNullOrBlank() ->
				"On-Device (${resolvedActiveDelegate.shortBadge}) • $localModelFileName"
			hasDedicatedNpu && isAiCoreAvailable -> "On-Device • Gemini Nano (NPU)"
			else -> "On-Device (${resolvedActiveDelegate.shortBadge}) • Reading Trail"
		}
		AiBackendMode.OLLAMA_LAN -> "Local • ${ollamaModelId.ifBlank { "LAN" }}"
		AiBackendMode.CLOUD_API -> when (cloudProvider) {
			CloudAiProvider.GOOGLE_GEMINI -> GeminiCloudModel.fromModelId(geminiModelId).displayName
			CloudAiProvider.OPENAI_COMPATIBLE -> "Cloud • ${openAiModelId.ifBlank { "OpenAI" }}"
		}
	}
}
