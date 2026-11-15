package org.koitharu.kotatsu.backup

import org.koitharu.kotatsu.backup.model.MihonBackupCategory
import org.koitharu.kotatsu.backup.model.MihonBackupChapter
import org.koitharu.kotatsu.backup.model.MihonBackupExtensionRepo
import org.koitharu.kotatsu.backup.model.MihonBackupHistory
import org.koitharu.kotatsu.backup.model.MihonBackupSource
import org.koitharu.kotatsu.backup.model.MihonBackupTracking
import org.koitharu.kotatsu.core.exceptions.BadBackupFormatException
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream

enum class BackupArchiveFormat {
	ZIP,
	GZIP_PROTO,
	RAW_PROTO,
	UNKNOWN,
}

data class NekoBackupMergeManga(
	val url: String = "",
	val title: String = "",
	val coverUrl: String = "",
	val mergeType: Int = 0,
)

data class DecodedTachibkManga(
	val source: Long = 0L,
	val url: String = "",
	val title: String = "",
	val artist: String? = null,
	val author: String? = null,
	val description: String? = null,
	val genre: List<String> = emptyList(),
	val status: Int = 0,
	val thumbnailUrl: String? = null,
	val dateAdded: Long = 0L,
	val chapters: List<MihonBackupChapter> = emptyList(),
	val categories: List<Long> = emptyList(),
	val tracking: List<MihonBackupTracking> = emptyList(),
	val favorite: Boolean = true,
	val history: List<MihonBackupHistory> = emptyList(),
	val lastModifiedAt: Long = 0L,
	val customTitle: String? = null,
	val alternativeArtwork: String? = null,
	val mergeMangaList: List<NekoBackupMergeManga> = emptyList(),
)

data class DecodedTachibkBackup(
	val backupManga: List<DecodedTachibkManga>,
	val backupCategories: List<MihonBackupCategory>,
	val backupSources: List<MihonBackupSource>,
	val backupExtensionRepo: List<MihonBackupExtensionRepo>,
)

/**
 * Resilient, wire-safe binary Protobuf decoder for Neko & Mihon `.tachibk` / `.proto.gz` backups.
 *
 * Unlike `kotlinx.serialization.protobuf` (which aborts if a submessage contains `@ProtoNumber(0)`
 * such as Neko's legacy `BrokenBackupHistory`), this decoder reads wire tags directly and safely
 * skips unknown or zero-numbered tags while preserving Neko-specific extensions (`800` customTitle,
 * `903` alternativeArtwork, `904` mergeMangaList).
 */
object NekoTachibkDecoder {

	const val MANGADEX_EN_SOURCE_ID: Long = 2499283573021220255L
	const val BUILTIN_MANGADEX_NAME: String = "MANGADEX"

	private val UUID_REGEX = Regex(
		"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
	)

	private val MD_LANG_CODES = listOf(
		"all", "en", "ja", "ko", "zh", "zh-hk", "zh-ro", "ja-ro", "ko-ro",
		"pt-br", "pt", "es", "es-la", "fr", "de", "it", "ru", "id", "vi",
		"th", "pl", "tr", "ar", "uk", "cs", "hu", "bg", "ro", "nl", "sv",
		"fi", "da", "no", "nb", "el", "he", "fa", "hi", "bn", "ms", "tl",
		"fil", "my", "mn", "kk", "uz", "az", "ka", "hy", "sq", "sr", "hr",
		"bs", "sl", "sk", "lt", "lv", "et", "ca", "eu", "gl", "eo", "la",
		"ne", "si", "ta", "te", "ur", "af", "jv",
	)

	val MANGADEX_SOURCE_IDS: Set<Long> by lazy {
		buildSet(MD_LANG_CODES.size + 2) {
			add(MANGADEX_EN_SOURCE_ID)
			for (lang in MD_LANG_CODES) {
				add(computeTachiyomiSourceId("mangadex", lang, 1))
			}
		}
	}

	fun computeTachiyomiSourceId(name: String, lang: String, versionId: Int = 1): Long {
		val key = "${name.lowercase(Locale.ROOT)}/$lang/$versionId"
		val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray(StandardCharsets.UTF_8))
		var value = 0L
		for (i in 0..7) {
			value = value or ((bytes[i].toLong() and 0xFFL) shl (8 * (7 - i)))
		}
		return value and Long.MAX_VALUE
	}

	fun isMangaDexSourceId(sourceId: Long): Boolean = sourceId in MANGADEX_SOURCE_IDS

	fun canonicalizeMangaDexMangaUrl(rawUrl: String): String {
		val trimmed = rawUrl.trim()
		if (trimmed.isEmpty()) return trimmed
		val match = UUID_REGEX.find(trimmed)
		if (match != null) {
			val lower = trimmed.lowercase(Locale.ROOT)
			if (
				lower.startsWith("/title/") ||
				lower.startsWith("/manga/") ||
				lower.startsWith("title/") ||
				lower.startsWith("manga/") ||
				lower.contains("mangadex.org/title/") ||
				lower.contains("mangadex.org/manga/") ||
				trimmed.length == 36
			) {
				return "/manga/${match.value.lowercase(Locale.ROOT)}"
			}
		}
		return trimmed
	}

	fun canonicalizeMangaDexChapterUrl(rawUrl: String): String {
		val trimmed = rawUrl.trim()
		if (trimmed.isEmpty()) return trimmed
		val match = UUID_REGEX.find(trimmed)
		if (match != null) {
			val lower = trimmed.lowercase(Locale.ROOT)
			if (
				lower.startsWith("/chapter/") ||
				lower.startsWith("chapter/") ||
				lower.contains("mangadex.org/chapter/") ||
				trimmed.length == 36
			) {
				return "/chapter/${match.value.lowercase(Locale.ROOT)}"
			}
		}
		return trimmed
	}

	fun nekoMergeTypeDisplayName(mergeType: Int): String = when (mergeType) {
		1 -> "Komga"
		2 -> "Toonily"
		3 -> "Weeb Central"
		4 -> "ComicK"
		5 -> "Suwayomi"
		6 -> "MangaSee"
		7 -> "MangaBall"
		8 -> "MangaBuddy"
		9 -> "Project Suki"
		10 -> "Comix"
		11 -> "Atsumaru"
		12 -> "Kagane"
		else -> "MergedSource_$mergeType"
	}

	fun sniffFormat(header: ByteArray, length: Int = header.size): BackupArchiveFormat {
		if (length < 2) return BackupArchiveFormat.UNKNOWN
		val b0 = header[0].toInt() and 0xFF
		val b1 = header[1].toInt() and 0xFF
		return when {
			b0 == 0x50 && b1 == 0x4B -> BackupArchiveFormat.ZIP
			b0 == 0x1F && b1 == 0x8B -> BackupArchiveFormat.GZIP_PROTO
			b0 == 0x0A || b0 == 0x12 -> BackupArchiveFormat.RAW_PROTO
			else -> BackupArchiveFormat.UNKNOWN
		}
	}

	fun sniffStream(input: InputStream): BackupArchiveFormat {
		val buf = ByteArray(4)
		val read = input.read(buf)
		return sniffFormat(buf, read)
	}

	fun decodeStream(rawInput: InputStream): DecodedTachibkBackup {
		val buffered = if (rawInput.markSupported()) rawInput else BufferedInputStream(rawInput, 65_536)
		buffered.mark(8)
		val header = ByteArray(4)
		val readCount = buffered.read(header)
		if (readCount <= 0) {
			throw BadBackupFormatException(EOFException("Empty backup stream"))
		}
		buffered.reset()

		val format = sniffFormat(header, readCount)
		if (format == BackupArchiveFormat.ZIP) {
			throw BadBackupFormatException(IllegalArgumentException("Expected .tachibk/.proto.gz, got ZIP"))
		}
		val payloadStream: InputStream = if (format == BackupArchiveFormat.GZIP_PROTO) {
			BufferedInputStream(GZIPInputStream(buffered, 65_536), 65_536)
		} else {
			buffered
		}

		return try {
			payloadStream.use { stream ->
				decodeRootBackup(ProtoInputStreamReader(stream))
			}
		} catch (e: BadBackupFormatException) {
			throw e
		} catch (e: Exception) {
			throw BadBackupFormatException(e)
		}
	}

	private fun decodeRootBackup(reader: ProtoInputStreamReader): DecodedTachibkBackup {
		val mangaList = ArrayList<DecodedTachibkManga>(512)
		val categories = ArrayList<MihonBackupCategory>(16)
		val sources = ArrayList<MihonBackupSource>(32)
		val repos = ArrayList<MihonBackupExtensionRepo>(4)

		while (true) {
			val tag = reader.readTagOrNull() ?: break
			val fieldNumber = tag ushr 3
			val wireType = tag and 0x07
			when {
				fieldNumber == 1 && wireType == WIRE_LEN -> {
					val bytes = reader.readBytes()
					mangaList += decodeManga(ByteArraySliceReader(bytes))
				}
				fieldNumber == 2 && wireType == WIRE_LEN -> {
					val bytes = reader.readBytes()
					categories += decodeCategory(ByteArraySliceReader(bytes))
				}
				fieldNumber == 101 && wireType == WIRE_LEN -> {
					val bytes = reader.readBytes()
					sources += decodeSource(ByteArraySliceReader(bytes))
				}
				fieldNumber == 106 && wireType == WIRE_LEN -> {
					val bytes = reader.readBytes()
					repos += decodeExtensionRepo(ByteArraySliceReader(bytes))
				}
				else -> reader.skipField(wireType)
			}
		}
		return DecodedTachibkBackup(
			backupManga = mangaList,
			backupCategories = categories,
			backupSources = sources,
			backupExtensionRepo = repos,
		)
	}

	private fun decodeManga(r: ByteArraySliceReader): DecodedTachibkManga {
		var source = 0L
		var url = ""
		var title = ""
		var artist: String? = null
		var author: String? = null
		var description: String? = null
		val genre = ArrayList<String>(8)
		var status = 0
		var thumbnailUrl: String? = null
		var dateAdded = 0L
		val chapters = ArrayList<MihonBackupChapter>()
		val categories = ArrayList<Long>(4)
		val tracking = ArrayList<MihonBackupTracking>(2)
		var favorite = true
		val history = ArrayList<MihonBackupHistory>(4)
		var lastModifiedAt = 0L
		var customTitle: String? = null
		var alternativeArtwork: String? = null
		val mergeMangaList = ArrayList<NekoBackupMergeManga>(2)

		while (!r.isAtEnd()) {
			val tag = r.readVarint32()
			val field = tag ushr 3
			val wire = tag and 0x07
			when (field) {
				1 -> if (wire == WIRE_VARINT) source = r.readVarint64() else r.skipField(wire)
				2 -> if (wire == WIRE_LEN) url = r.readString() else r.skipField(wire)
				3 -> if (wire == WIRE_LEN) title = r.readString() else r.skipField(wire)
				4 -> if (wire == WIRE_LEN) artist = r.readString() else r.skipField(wire)
				5 -> if (wire == WIRE_LEN) author = r.readString() else r.skipField(wire)
				6 -> if (wire == WIRE_LEN) description = r.readString() else r.skipField(wire)
				7 -> if (wire == WIRE_LEN) genre += r.readString() else r.skipField(wire)
				8 -> if (wire == WIRE_VARINT) status = r.readVarint32() else r.skipField(wire)
				9 -> if (wire == WIRE_LEN) thumbnailUrl = r.readString() else r.skipField(wire)
				13 -> if (wire == WIRE_VARINT) dateAdded = r.readVarint64() else r.skipField(wire)
				16 -> if (wire == WIRE_LEN) chapters += decodeChapter(r.readSubReader()) else r.skipField(wire)
				17 -> when (wire) {
					WIRE_VARINT -> categories += r.readVarint64()
					WIRE_LEN -> {
						val sub = r.readSubReader()
						while (!sub.isAtEnd()) {
							categories += sub.readVarint64()
						}
					}
					else -> r.skipField(wire)
				}
				18 -> if (wire == WIRE_LEN) tracking += decodeTracking(r.readSubReader()) else r.skipField(wire)
				100 -> if (wire == WIRE_VARINT) favorite = r.readVarint64() != 0L else r.skipField(wire)
				104 -> if (wire == WIRE_LEN) history += decodeHistory(r.readSubReader()) else r.skipField(wire)
				106 -> if (wire == WIRE_VARINT) lastModifiedAt = r.readVarint64() else r.skipField(wire)
				800 -> if (wire == WIRE_LEN) customTitle = r.readString().takeIf { it.isNotBlank() } else r.skipField(wire)
				903 -> if (wire == WIRE_LEN) alternativeArtwork = r.readString().takeIf { it.isNotBlank() } else r.skipField(wire)
				904 -> if (wire == WIRE_LEN) mergeMangaList += decodeMergeManga(r.readSubReader()) else r.skipField(wire)
				else -> r.skipField(wire)
			}
		}

		return DecodedTachibkManga(
			source = source,
			url = url,
			title = title,
			artist = artist,
			author = author,
			description = description,
			genre = genre,
			status = status,
			thumbnailUrl = thumbnailUrl,
			dateAdded = dateAdded,
			chapters = chapters,
			categories = categories,
			tracking = tracking,
			favorite = favorite,
			history = history,
			lastModifiedAt = lastModifiedAt,
			customTitle = customTitle,
			alternativeArtwork = alternativeArtwork,
			mergeMangaList = mergeMangaList,
		)
	}

	private fun decodeChapter(r: ByteArraySliceReader): MihonBackupChapter {
		var url = ""
		var name = ""
		var scanlator: String? = null
		var read = false
		var bookmark = false
		var lastPageRead = 0L
		var dateFetch = 0L
		var dateUpload = 0L
		var chapterNumber = 0f
		var sourceOrder = 0L
		var lastModifiedAt = 0L
		var version = 0L

		while (!r.isAtEnd()) {
			val tag = r.readVarint32()
			val field = tag ushr 3
			val wire = tag and 0x07
			when (field) {
				1 -> if (wire == WIRE_LEN) url = r.readString() else r.skipField(wire)
				2 -> if (wire == WIRE_LEN) name = r.readString() else r.skipField(wire)
				3 -> if (wire == WIRE_LEN) scanlator = r.readString() else r.skipField(wire)
				4 -> if (wire == WIRE_VARINT) read = r.readVarint64() != 0L else r.skipField(wire)
				5 -> if (wire == WIRE_VARINT) bookmark = r.readVarint64() != 0L else r.skipField(wire)
				6 -> if (wire == WIRE_VARINT) lastPageRead = r.readVarint64() else r.skipField(wire)
				7 -> if (wire == WIRE_VARINT) dateFetch = r.readVarint64() else r.skipField(wire)
				8 -> if (wire == WIRE_VARINT) dateUpload = r.readVarint64() else r.skipField(wire)
				9 -> if (wire == WIRE_FIXED32) chapterNumber = r.readFloat() else r.skipField(wire)
				10 -> if (wire == WIRE_VARINT) sourceOrder = r.readVarint64() else r.skipField(wire)
				11 -> if (wire == WIRE_VARINT) lastModifiedAt = r.readVarint64() else r.skipField(wire)
				12 -> if (wire == WIRE_VARINT) version = r.readVarint64() else r.skipField(wire)
				else -> r.skipField(wire)
			}
		}

		return MihonBackupChapter(
			url = url,
			name = name,
			scanlator = scanlator,
			read = read,
			bookmark = bookmark,
			lastPageRead = lastPageRead,
			dateFetch = dateFetch,
			dateUpload = dateUpload,
			chapterNumber = chapterNumber,
			sourceOrder = sourceOrder,
			lastModifiedAt = lastModifiedAt,
			version = version,
		)
	}

	private fun decodeHistory(r: ByteArraySliceReader): MihonBackupHistory {
		var url = ""
		var lastRead = 0L
		var readDuration = 0L
		while (!r.isAtEnd()) {
			val tag = r.readVarint32()
			val field = tag ushr 3
			val wire = tag and 0x07
			when (field) {
				1 -> if (wire == WIRE_LEN) url = r.readString() else r.skipField(wire)
				2 -> if (wire == WIRE_VARINT) lastRead = r.readVarint64() else r.skipField(wire)
				3 -> if (wire == WIRE_VARINT) readDuration = r.readVarint64() else r.skipField(wire)
				else -> r.skipField(wire)
			}
		}
		return MihonBackupHistory(url = url, lastRead = lastRead, readDuration = readDuration)
	}

	private fun decodeCategory(r: ByteArraySliceReader): MihonBackupCategory {
		var name = ""
		var order = 0L
		var id = 0L
		var flags = 0L
		while (!r.isAtEnd()) {
			val tag = r.readVarint32()
			val field = tag ushr 3
			val wire = tag and 0x07
			when (field) {
				1 -> if (wire == WIRE_LEN) name = r.readString() else r.skipField(wire)
				2 -> if (wire == WIRE_VARINT) order = r.readVarint64() else r.skipField(wire)
				3 -> if (wire == WIRE_VARINT) id = r.readVarint64() else r.skipField(wire)
				100 -> if (wire == WIRE_VARINT) flags = r.readVarint64() else r.skipField(wire)
				else -> r.skipField(wire)
			}
		}
		return MihonBackupCategory(name = name, order = order, id = id, flags = flags)
	}

	private fun decodeTracking(r: ByteArraySliceReader): MihonBackupTracking {
		var syncId = 0
		var libraryId = 0L
		var mediaIdInt = 0
		var trackingUrl = ""
		var title = ""
		var lastChapterRead = 0f
		var totalChapters = 0
		var score = 0f
		var status = 0
		var startedReadingDate = 0L
		var finishedReadingDate = 0L
		var isPrivate = false
		var mediaId = 0L

		while (!r.isAtEnd()) {
			val tag = r.readVarint32()
			val field = tag ushr 3
			val wire = tag and 0x07
			when (field) {
				1 -> if (wire == WIRE_VARINT) syncId = r.readVarint32() else r.skipField(wire)
				2 -> if (wire == WIRE_VARINT) libraryId = r.readVarint64() else r.skipField(wire)
				3 -> if (wire == WIRE_VARINT) mediaIdInt = r.readVarint32() else r.skipField(wire)
				4 -> if (wire == WIRE_LEN) trackingUrl = r.readString() else r.skipField(wire)
				5 -> if (wire == WIRE_LEN) title = r.readString() else r.skipField(wire)
				6 -> if (wire == WIRE_FIXED32) lastChapterRead = r.readFloat() else r.skipField(wire)
				7 -> if (wire == WIRE_VARINT) totalChapters = r.readVarint32() else r.skipField(wire)
				8 -> if (wire == WIRE_FIXED32) score = r.readFloat() else r.skipField(wire)
				9 -> if (wire == WIRE_VARINT) status = r.readVarint32() else r.skipField(wire)
				10 -> if (wire == WIRE_VARINT) startedReadingDate = r.readVarint64() else r.skipField(wire)
				11 -> if (wire == WIRE_VARINT) finishedReadingDate = r.readVarint64() else r.skipField(wire)
				12 -> if (wire == WIRE_VARINT) isPrivate = r.readVarint64() != 0L else r.skipField(wire)
				100 -> if (wire == WIRE_VARINT) mediaId = r.readVarint64() else r.skipField(wire)
				else -> r.skipField(wire)
			}
		}

		return MihonBackupTracking(
			syncId = syncId,
			libraryId = libraryId,
			mediaIdInt = mediaIdInt,
			trackingUrl = trackingUrl,
			title = title,
			lastChapterRead = lastChapterRead,
			totalChapters = totalChapters,
			score = score,
			status = status,
			startedReadingDate = startedReadingDate,
			finishedReadingDate = finishedReadingDate,
			private = isPrivate,
			mediaId = mediaId,
		)
	}

	private fun decodeMergeManga(r: ByteArraySliceReader): NekoBackupMergeManga {
		var url = ""
		var title = ""
		var coverUrl = ""
		var mergeType = 0
		while (!r.isAtEnd()) {
			val tag = r.readVarint32()
			val field = tag ushr 3
			val wire = tag and 0x07
			when (field) {
				1 -> if (wire == WIRE_LEN) url = r.readString() else r.skipField(wire)
				2 -> if (wire == WIRE_LEN) title = r.readString() else r.skipField(wire)
				3 -> if (wire == WIRE_LEN) coverUrl = r.readString() else r.skipField(wire)
				4 -> if (wire == WIRE_VARINT) mergeType = r.readVarint32() else r.skipField(wire)
				else -> r.skipField(wire)
			}
		}
		return NekoBackupMergeManga(url = url, title = title, coverUrl = coverUrl, mergeType = mergeType)
	}

	private fun decodeSource(r: ByteArraySliceReader): MihonBackupSource {
		var name = ""
		var sourceId = 0L
		while (!r.isAtEnd()) {
			val tag = r.readVarint32()
			val field = tag ushr 3
			val wire = tag and 0x07
			when (field) {
				1 -> if (wire == WIRE_LEN) name = r.readString() else r.skipField(wire)
				2 -> if (wire == WIRE_VARINT) sourceId = r.readVarint64() else r.skipField(wire)
				else -> r.skipField(wire)
			}
		}
		return MihonBackupSource(name = name, sourceId = sourceId)
	}

	private fun decodeExtensionRepo(r: ByteArraySliceReader): MihonBackupExtensionRepo {
		var baseUrl = ""
		var name = ""
		var shortName: String? = null
		var website = ""
		var fingerprint = ""
		while (!r.isAtEnd()) {
			val tag = r.readVarint32()
			val field = tag ushr 3
			val wire = tag and 0x07
			when (field) {
				1 -> if (wire == WIRE_LEN) baseUrl = r.readString() else r.skipField(wire)
				2 -> if (wire == WIRE_LEN) name = r.readString() else r.skipField(wire)
				3 -> if (wire == WIRE_LEN) shortName = r.readString() else r.skipField(wire)
				4 -> if (wire == WIRE_LEN) website = r.readString() else r.skipField(wire)
				5 -> if (wire == WIRE_LEN) fingerprint = r.readString() else r.skipField(wire)
				else -> r.skipField(wire)
			}
		}
		return MihonBackupExtensionRepo(
			baseUrl = baseUrl,
			name = name,
			shortName = shortName,
			website = website,
			signingKeyFingerprint = fingerprint,
		)
	}

	private const val WIRE_VARINT = 0
	private const val WIRE_FIXED64 = 1
	private const val WIRE_LEN = 2
	private const val WIRE_START_GROUP = 3
	private const val WIRE_END_GROUP = 4
	private const val WIRE_FIXED32 = 5

	private class ProtoInputStreamReader(private val input: InputStream) {

		fun readTagOrNull(): Int? {
			val first = input.read()
			if (first == -1) return null
			var result = first and 0x7F
			if ((first and 0x80) == 0) return result
			var shift = 7
			while (shift < 32) {
				val b = input.read()
				if (b == -1) throw EOFException("Truncated varint tag")
				result = result or ((b and 0x7F) shl shift)
				if ((b and 0x80) == 0) return result
				shift += 7
			}
			while (true) {
				val b = input.read()
				if (b == -1) throw EOFException("Truncated 64-bit varint tag")
				if ((b and 0x80) == 0) return result
			}
		}

		fun readVarint64(): Long {
			var shift = 0
			var result = 0L
			while (shift < 64) {
				val b = input.read()
				if (b == -1) throw EOFException("Truncated varint64")
				result = result or ((b.toLong() and 0x7FL) shl shift)
				if ((b and 0x80) == 0) return result
				shift += 7
			}
			throw BadBackupFormatException(IllegalStateException("Malformed varint64"))
		}

		fun readVarint32(): Int = readVarint64().toInt()

		fun readBytes(): ByteArray {
			val len = readVarint32()
			if (len < 0 || len > 128 * 1024 * 1024) {
				throw BadBackupFormatException(IllegalStateException("Invalid length-delimited size: $len"))
			}
			if (len == 0) return ByteArray(0)
			val out = ByteArray(len)
			var offset = 0
			while (offset < len) {
				val n = input.read(out, offset, len - offset)
				if (n <= 0) throw EOFException("Truncated length-delimited field ($offset/$len)")
				offset += n
			}
			return out
		}

		fun skipExact(count: Long) {
			var remaining = count
			val skipBuf = ByteArray(4096)
			while (remaining > 0L) {
				val skipped = input.skip(remaining)
				if (skipped > 0L) {
					remaining -= skipped
				} else {
					val toRead = minOf(remaining, skipBuf.size.toLong()).toInt()
					val read = input.read(skipBuf, 0, toRead)
					if (read <= 0) throw EOFException("Truncated skip")
					remaining -= read
				}
			}
		}

		fun skipField(wireType: Int) {
			when (wireType) {
				WIRE_VARINT -> readVarint64()
				WIRE_FIXED64 -> skipExact(8)
				WIRE_LEN -> skipExact(readVarint32().toLong().coerceAtLeast(0L))
				WIRE_START_GROUP -> {
					while (true) {
						val inner = readTagOrNull() ?: break
						if ((inner and 0x07) == WIRE_END_GROUP) break
						skipField(inner and 0x07)
					}
				}
				WIRE_END_GROUP -> Unit
				WIRE_FIXED32 -> skipExact(4)
				else -> throw BadBackupFormatException(IllegalStateException("Unknown wireType=$wireType"))
			}
		}
	}

	private class ByteArraySliceReader(
		private val buf: ByteArray,
		private var pos: Int = 0,
		private val limit: Int = buf.size,
	) {

		fun isAtEnd(): Boolean = pos >= limit

		fun readVarint64(): Long {
			var shift = 0
			var result = 0L
			while (shift < 64) {
				if (pos >= limit) throw EOFException("Slice varint64 overflow")
				val b = buf[pos++].toInt()
				result = result or ((b.toLong() and 0x7FL) shl shift)
				if ((b and 0x80) == 0) return result
				shift += 7
			}
			throw BadBackupFormatException(IllegalStateException("Malformed slice varint64"))
		}

		fun readVarint32(): Int = readVarint64().toInt()

		fun readFloat(): Float {
			if (pos + 4 > limit) throw EOFException("Slice float overflow")
			val bits = (buf[pos].toInt() and 0xFF) or
				((buf[pos + 1].toInt() and 0xFF) shl 8) or
				((buf[pos + 2].toInt() and 0xFF) shl 16) or
				((buf[pos + 3].toInt() and 0xFF) shl 24)
			pos += 4
			return Float.fromBits(bits)
		}

		fun readString(): String {
			val len = readVarint32()
			if (len <= 0) return ""
			if (pos + len > limit) throw EOFException("Slice string overflow ($len)")
			val str = String(buf, pos, len, StandardCharsets.UTF_8)
			pos += len
			return str
		}

		fun readSubReader(): ByteArraySliceReader {
			val len = readVarint32()
			if (len < 0 || pos + len > limit) throw EOFException("Slice submessage overflow ($len)")
			val sub = ByteArraySliceReader(buf, pos, pos + len)
			pos += len
			return sub
		}

		fun skipField(wireType: Int) {
			when (wireType) {
				WIRE_VARINT -> readVarint64()
				WIRE_FIXED64 -> {
					if (pos + 8 > limit) throw EOFException("Slice fixed64 overflow")
					pos += 8
				}
				WIRE_LEN -> {
					val len = readVarint32().coerceAtLeast(0)
					if (pos + len > limit) throw EOFException("Slice len overflow")
					pos += len
				}
				WIRE_START_GROUP -> {
					while (!isAtEnd()) {
						val inner = readVarint32()
						if ((inner and 0x07) == WIRE_END_GROUP) break
						skipField(inner and 0x07)
					}
				}
				WIRE_END_GROUP -> Unit
				WIRE_FIXED32 -> {
					if (pos + 4 > limit) throw EOFException("Slice fixed32 overflow")
					pos += 4
				}
				else -> throw BadBackupFormatException(IllegalStateException("Unknown slice wireType=$wireType"))
			}
		}
	}
}
