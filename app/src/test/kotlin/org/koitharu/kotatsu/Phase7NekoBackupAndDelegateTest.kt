package org.koitharu.kotatsu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.backup.BackupArchiveFormat
import org.koitharu.kotatsu.backup.NekoTachibkDecoder
import org.koitharu.kotatsu.core.ai.AiComputeDelegate
import org.koitharu.kotatsu.core.ai.LocalModelFormat
import org.koitharu.kotatsu.core.os.HardwareAcceleratorProbe
import org.koitharu.kotatsu.core.os.ResolvedUpscaleBackend
import org.koitharu.kotatsu.core.parser.SourceShortCodeResolver
import org.koitharu.kotatsu.core.prefs.PageCounterPlacement
import org.koitharu.kotatsu.core.prefs.UpscaleComputeMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

class Phase7NekoBackupAndDelegateTest {

	@Test
	fun `decodes Neko tachibk GZIP protobuf with MangaDex UUID normalization, broken history tag 0, and merge links`() {
		val mdUuid = "a1c7c817-4e59-43b7-9365-09675a149a6f"
		val chUuid = "f4b82e11-1234-4abc-9def-112233445566"

		// Build BackupCategory (name="Reading", order=0, id=0)
		val categoryBytes = protoMessage {
			writeString(1, "Reading")
			writeVarint(2, 0L)
			writeVarint(3, 0L)
		}

		// Build BackupChapter (url="/chapter/<uuid>", name="Ch. 42.5", read=true, lastPageRead=19, chapterNumber=42.5f)
		val chapterBytes = protoMessage {
			writeString(1, "/chapter/$chUuid")
			writeString(2, "Vol. 5 Ch. 42.5 - Midnight Duel")
			writeString(3, "Cyan Scans")
			writeVarint(4, 1L)
			writeVarint(6, 19L)
			writeFloat(9, 42.5f)
		}

		// Build legacy Neko BrokenBackupHistory (@ProtoNumber(0) url -> wire tag (0 shl 3) or 2 = 2!)
		// kotlinx.serialization.protobuf crashes on field number 0; NekoTachibkDecoder must skip it cleanly.
		val brokenHistoryEntry = ByteArrayOutputStream().apply {
			val urlBytes = "/chapter/$chUuid".toByteArray(StandardCharsets.UTF_8)
			writeRawVarint(0 shl 3 or 2) // fieldNumber = 0, wireType = LEN
			writeRawVarint(urlBytes.size)
			write(urlBytes)
			writeRawVarint(1 shl 3 or 0) // lastRead = 1700000000000L
			writeRawVarint64(1_700_000_000_000L)
		}.toByteArray()

		// Build valid BackupHistory (104)
		val validHistoryBytes = protoMessage {
			writeString(1, "/chapter/$chUuid")
			writeVarint(2, 1_710_000_000_000L)
			writeVarint(3, 420_000L)
		}

		// Build Neko BackupMergeManga (904): mergeType = 4 (ComicK)
		val mergeMangaBytes = protoMessage {
			writeString(1, "/comic/00-omniscient-reader")
			writeString(2, "Omniscient Reader's Viewpoint")
			writeString(3, "https://meo.comick.pictures/cover.jpg")
			writeVarint(4, 4L)
		}

		// Build BackupManga #1 (categorized + Neko extensions)
		val manga1Bytes = protoMessage {
			writeVarint(1, NekoTachibkDecoder.MANGADEX_EN_SOURCE_ID)
			writeString(2, "/title/$mdUuid")
			writeString(3, "Omniscient Reader")
			writeString(4, "Sleepy-C")
			writeString(5, "sing N song")
			writeString(6, "Only I know the end of this world.")
			writeString(7, "Action")
			writeString(7, "Fantasy")
			writeBytes(16, chapterBytes)
			writeVarint(17, 0L)
			writeBytes(103, brokenHistoryEntry)
			writeBytes(104, validHistoryBytes)
			writeString(800, "Omniscient Reader (Custom)")
			writeString(904, String(mergeMangaBytes, StandardCharsets.ISO_8859_1).let { "" }) // overridden below
		}.let { base ->
			// Append field 904 properly as raw bytes
			ByteArrayOutputStream().apply {
				write(base)
				writeRawVarint((904 shl 3) or 2)
				writeRawVarint(mergeMangaBytes.size)
				write(mergeMangaBytes)
			}.toByteArray()
		}

		// Build BackupManga #2 (uncategorized -> must preserve empty categories for Default assignment)
		val manga2Bytes = protoMessage {
			writeVarint(1, NekoTachibkDecoder.computeTachiyomiSourceId("mangadex", "ja", 1))
			writeString(2, "https://mangadex.org/title/$mdUuid/omniscient-reader")
			writeString(3, "Uncategorized Series")
		}

		// Build Root Backup (field 1 = backupManga, field 2 = backupCategories, field 101 = backupSources)
		val sourceEntry = protoMessage {
			writeString(1, "MangaDex")
			writeVarint(2, NekoTachibkDecoder.MANGADEX_EN_SOURCE_ID)
		}
		val rawRootProto = protoMessage {
			writeBytes(1, manga1Bytes)
			writeBytes(1, manga2Bytes)
			writeBytes(2, categoryBytes)
			writeBytes(101, sourceEntry)
		}

		val gzipBytes = ByteArrayOutputStream().use { out ->
			GZIPOutputStream(out).use { gz -> gz.write(rawRootProto) }
			out.toByteArray()
		}

		assertEquals(BackupArchiveFormat.GZIP_PROTO, NekoTachibkDecoder.sniffFormat(gzipBytes))
		assertEquals(
			BackupArchiveFormat.ZIP,
			NekoTachibkDecoder.sniffFormat(byteArrayOf(0x50, 0x4B, 0x03, 0x04)),
		)

		val decoded = NekoTachibkDecoder.decodeStream(ByteArrayInputStream(gzipBytes))
		assertEquals(1, decoded.backupCategories.size)
		assertEquals("Reading", decoded.backupCategories.first().name)
		assertEquals(2, decoded.backupManga.size)

		val m1 = decoded.backupManga[0]
		assertTrue(NekoTachibkDecoder.isMangaDexSourceId(m1.source))
		assertEquals("/manga/$mdUuid", NekoTachibkDecoder.canonicalizeMangaDexMangaUrl(m1.url))
		assertEquals("Omniscient Reader (Custom)", m1.customTitle)
		assertEquals(listOf(0L), m1.categories)
		assertEquals(1, m1.chapters.size)
		assertTrue(m1.chapters.first().read)
		assertEquals(19L, m1.chapters.first().lastPageRead)
		assertEquals(42.5f, m1.chapters.first().chapterNumber, 0.001f)
		assertEquals("/chapter/$chUuid", NekoTachibkDecoder.canonicalizeMangaDexChapterUrl(m1.chapters.first().url))
		assertEquals(1, m1.history.size)
		assertEquals(1_710_000_000_000L, m1.history.first().lastRead)
		assertEquals(1, m1.mergeMangaList.size)
		assertEquals("ComicK", NekoTachibkDecoder.nekoMergeTypeDisplayName(m1.mergeMangaList.first().mergeType))

		val m2 = decoded.backupManga[1]
		assertTrue(NekoTachibkDecoder.isMangaDexSourceId(m2.source))
		assertTrue(m2.categories.isEmpty())
		assertEquals("/manga/$mdUuid", NekoTachibkDecoder.canonicalizeMangaDexMangaUrl(m2.url))
	}

	@Test
	fun `resolves GPU and multi-core CPU delegate cascades cleanly on Snapdragon 8 Gen 2 SM8550 and older APIs`() {
		// Snapdragon 8 Gen 2 (SM8550) does not expose public AICore/LiteRT GenAI NPU bindings
		assertFalse(
			HardwareAcceleratorProbe.isNpuRuntimeExposed(
				socModel = "SM8550",
				sdkInt = 34,
				isAiCorePresent = true,
			),
		)
		assertFalse(
			HardwareAcceleratorProbe.isNpuRuntimeExposed(
				socModel = "SM8550",
				sdkInt = 34,
				isAiCorePresent = false,
			),
		)
		// Newer NPU SoC (e.g. SM8750 / Tensor G4) with AICore on Android 14+ exposes NPU
		assertTrue(
			HardwareAcceleratorProbe.isNpuRuntimeExposed(
				socModel = "SM8750",
				sdkInt = 35,
				isAiCorePresent = true,
			),
		)

		// AUTO on SM8550 (hasExposedNpu = false, hasGpuCompute = true) -> [GPU, CPU]
		assertEquals(
			listOf(AiComputeDelegate.GPU, AiComputeDelegate.CPU),
			HardwareAcceleratorProbe.resolveAiDelegateChain(
				requested = AiComputeDelegate.AUTO,
				hasExposedNpu = false,
				hasGpuCompute = true,
			),
		)

		// AUTO when NPU runtime IS exposed -> [NPU, GPU, CPU]
		assertEquals(
			listOf(AiComputeDelegate.NPU, AiComputeDelegate.GPU, AiComputeDelegate.CPU),
			HardwareAcceleratorProbe.resolveAiDelegateChain(
				requested = AiComputeDelegate.AUTO,
				hasExposedNpu = true,
				hasGpuCompute = true,
			),
		)

		// Explicit NPU selection on non-NPU device safely falls back to [GPU, CPU] without crashing
		assertEquals(
			listOf(AiComputeDelegate.GPU, AiComputeDelegate.CPU),
			HardwareAcceleratorProbe.resolveAiDelegateChain(
				requested = AiComputeDelegate.NPU,
				hasExposedNpu = false,
				hasGpuCompute = true,
			),
		)

		// Upscaler compute backend resolution across API levels and explicit CPU override
		assertEquals(
			ResolvedUpscaleBackend.GPU_AGSL,
			HardwareAcceleratorProbe.resolveUpscaleBackend(UpscaleComputeMode.AUTO, sdkInt = 34),
		)
		assertEquals(
			ResolvedUpscaleBackend.CPU_MULTICORE,
			HardwareAcceleratorProbe.resolveUpscaleBackend(UpscaleComputeMode.AUTO, sdkInt = 32),
		)
		assertEquals(
			ResolvedUpscaleBackend.CPU_MULTICORE,
			HardwareAcceleratorProbe.resolveUpscaleBackend(UpscaleComputeMode.CPU, sdkInt = 34),
		)
		assertEquals(6, HardwareAcceleratorProbe.recommendedCpuWorkerThreads(8))
		assertEquals(4, HardwareAcceleratorProbe.recommendedCpuWorkerThreads(6))

		// Local model weight format detection (.litertlm / .task / .gguf)
		assertEquals(LocalModelFormat.LITERT_LM, LocalModelFormat.fromFileName("gemma3-1b-it-int4.litertlm"))
		assertEquals(LocalModelFormat.MEDIAPIPE_TASK, LocalModelFormat.fromFileName("gemma-2b-it-gpu-int4.task"))
		assertEquals(LocalModelFormat.GGUF, LocalModelFormat.fromFileName("Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"))
	}

	@Test
	fun `verifies PageCounterPlacement visibility flags and compact cover SourceShortCodeResolver`() {
		assertTrue(PageCounterPlacement.BOTTOM_CENTER.showsBottomCounter)
		assertFalse(PageCounterPlacement.BOTTOM_CENTER.showsTopBarCounter)

		assertFalse(PageCounterPlacement.TOP_BAR.showsBottomCounter)
		assertTrue(PageCounterPlacement.TOP_BAR.showsTopBarCounter)

		assertTrue(PageCounterPlacement.BOTH.showsBottomCounter)
		assertTrue(PageCounterPlacement.BOTH.showsTopBarCounter)

		assertFalse(PageCounterPlacement.HIDDEN.showsBottomCounter)
		assertFalse(PageCounterPlacement.HIDDEN.showsTopBarCounter)

		assertEquals("MD", SourceShortCodeResolver.resolveFromLabel("MangaDex (EN)", "MANGADEX"))
		assertEquals("M+", SourceShortCodeResolver.resolveFromLabel("MANGA Plus by SHUEISHA"))
		assertEquals("MP", SourceShortCodeResolver.resolveFromLabel("MangaPark"))
		assertEquals("BT", SourceShortCodeResolver.resolveFromLabel("Bato.to"))
		assertEquals("CK", SourceShortCodeResolver.resolveFromLabel("ComicK"))
		assertEquals("AS", SourceShortCodeResolver.resolveFromLabel("Asura Scans"))
		assertEquals("WC", SourceShortCodeResolver.resolveFromLabel("Weeb Central"))
		assertEquals("LOC", SourceShortCodeResolver.resolveFromLabel("Local"))
	}

	private class ProtoBuilder {
		private val out = ByteArrayOutputStream()

		fun writeVarint(fieldNumber: Int, value: Long) {
			out.writeRawVarint((fieldNumber shl 3) or 0)
			out.writeRawVarint64(value)
		}

		fun writeFloat(fieldNumber: Int, value: Float) {
			out.writeRawVarint((fieldNumber shl 3) or 5)
			val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
			out.write(buf)
		}

		fun writeString(fieldNumber: Int, value: String) {
			if (value.isEmpty()) return
			writeBytes(fieldNumber, value.toByteArray(StandardCharsets.UTF_8))
		}

		fun writeBytes(fieldNumber: Int, bytes: ByteArray) {
			out.writeRawVarint((fieldNumber shl 3) or 2)
			out.writeRawVarint(bytes.size)
			out.write(bytes)
		}

		fun toByteArray(): ByteArray = out.toByteArray()
	}

	private fun protoMessage(block: ProtoBuilder.() -> Unit): ByteArray =
		ProtoBuilder().apply(block).toByteArray()
}

private fun ByteArrayOutputStream.writeRawVarint(value: Int) {
	var v = value
	while ((v and 0x7F.inv()) != 0) {
		write((v and 0x7F) or 0x80)
		v = v ushr 7
	}
	write(v and 0x7F)
}

private fun ByteArrayOutputStream.writeRawVarint64(value: Long) {
	var v = value
	while ((v and 0x7FL.inv()) != 0L) {
		write(((v.toInt() and 0x7F) or 0x80))
		v = v ushr 7
	}
	write(v.toInt() and 0x7F)
}
