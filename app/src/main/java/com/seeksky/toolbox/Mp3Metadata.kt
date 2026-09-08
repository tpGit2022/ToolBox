package com.seeksky.toolbox

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.mp3.MP3File
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.AndroidArtwork
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

data class Mp3EditableFields(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val composer: String = "",
    val genre: String = "",
    val year: String = "",
    val track: String = "",
    val trackTotal: String = "",
    val disc: String = "",
    val discTotal: String = "",
    val comment: String = "",
    val lyrics: String = ""
)

data class Mp3AudioProperties(
    val durationSeconds: Double,
    val bitRate: String,
    val sampleRate: String,
    val channels: String,
    val encodingType: String,
    val format: String,
    val variableBitRate: Boolean
)

data class Mp3DocumentInfo(
    val displayName: String,
    val fileSize: Long,
    val fields: Mp3EditableFields,
    val audio: Mp3AudioProperties,
    val artwork: ByteArray?
)

sealed interface ArtworkUpdate {
    data object Keep : ArtworkUpdate
    data object Remove : ArtworkUpdate
    data class Replace(val bytes: ByteArray, val mimeType: String) : ArtworkUpdate
}

object Mp3MetadataRepository {
    private const val MAX_ARTWORK_BYTES = 15 * 1024 * 1024

    init {
        // This selects jaudiotagger's implementation that does not depend on desktop image APIs.
        TagOptionSingleton.getInstance().isAndroid = true
    }

    fun read(context: Context, uri: Uri): Mp3DocumentInfo {
        val document = queryDocument(context, uri)
        return withTemporaryMp3(context, uri) { file ->
            val audioFile = try {
                AudioFileIO.read(file)
            } catch (error: Exception) {
                throw IllegalArgumentException("无法解析该文件，请确认它是有效的 MP3 文件", error)
            }
            val tag = audioFile.tag
            val header = audioFile.audioHeader
            Mp3DocumentInfo(
                displayName = document.first,
                fileSize = document.second.takeIf { it >= 0 } ?: file.length(),
                fields = Mp3EditableFields(
                    title = tag.value(FieldKey.TITLE),
                    artist = tag.value(FieldKey.ARTIST),
                    album = tag.value(FieldKey.ALBUM),
                    albumArtist = tag.value(FieldKey.ALBUM_ARTIST),
                    composer = tag.value(FieldKey.COMPOSER),
                    genre = tag.value(FieldKey.GENRE),
                    year = tag.value(FieldKey.YEAR),
                    track = tag.value(FieldKey.TRACK),
                    trackTotal = tag.value(FieldKey.TRACK_TOTAL),
                    disc = tag.value(FieldKey.DISC_NO),
                    discTotal = tag.value(FieldKey.DISC_TOTAL),
                    comment = tag.value(FieldKey.COMMENT),
                    lyrics = tag.value(FieldKey.LYRICS)
                ),
                audio = Mp3AudioProperties(
                    durationSeconds = header.preciseTrackLength,
                    bitRate = header.bitRate.orEmpty(),
                    sampleRate = header.sampleRate.orEmpty(),
                    channels = header.channels.orEmpty(),
                    encodingType = header.encodingType.orEmpty(),
                    format = header.format.orEmpty(),
                    variableBitRate = header.isVariableBitRate
                ),
                artwork = tag?.firstArtwork?.binaryData?.copyOf()
            )
        }
    }

    fun write(
        context: Context,
        uri: Uri,
        fields: Mp3EditableFields,
        artworkUpdate: ArtworkUpdate
    ) {
        withTemporaryMp3(context, uri) { originalFile ->
            val editedFile = File.createTempFile("toolbox_mp3_edit_", ".mp3", context.cacheDir)
            try {
                originalFile.copyTo(editedFile, overwrite = true)
                val audioFile = try {
                    AudioFileIO.read(editedFile)
                } catch (error: Exception) {
                    throw IllegalArgumentException("无法解析该文件，请确认它是有效的 MP3 文件", error)
                }
                // Preserve the file's current ID3v2 version; new tags use jaudiotagger's
                // broadly compatible ID3v2.3 default.
                val tag = audioFile.tagOrCreateAndSetDefault
                tag.put(FieldKey.TITLE, fields.title)
                tag.put(FieldKey.ARTIST, fields.artist)
                tag.put(FieldKey.ALBUM, fields.album)
                tag.put(FieldKey.ALBUM_ARTIST, fields.albumArtist)
                tag.put(FieldKey.COMPOSER, fields.composer)
                tag.put(FieldKey.GENRE, fields.genre)
                tag.put(FieldKey.YEAR, fields.year)
                tag.put(FieldKey.TRACK, fields.track)
                tag.put(FieldKey.TRACK_TOTAL, fields.trackTotal)
                tag.put(FieldKey.DISC_NO, fields.disc)
                tag.put(FieldKey.DISC_TOTAL, fields.discTotal)
                tag.put(FieldKey.COMMENT, fields.comment)
                tag.put(FieldKey.LYRICS, fields.lyrics)

                // Keep an existing legacy ID3v1 tag in sync for older players that prefer it.
                (audioFile as? MP3File)?.getID3v1Tag()?.let { legacyTag ->
                    listOf(
                        FieldKey.TITLE to fields.title,
                        FieldKey.ARTIST to fields.artist,
                        FieldKey.ALBUM to fields.album,
                        FieldKey.GENRE to fields.genre,
                        FieldKey.YEAR to fields.year,
                        FieldKey.TRACK to fields.track,
                        FieldKey.COMMENT to fields.comment
                    ).forEach { (key, value) -> runCatching { legacyTag.put(key, value) } }
                }

                when (artworkUpdate) {
                    ArtworkUpdate.Keep -> Unit
                    ArtworkUpdate.Remove -> tag.deleteArtworkField()
                    is ArtworkUpdate.Replace -> {
                        tag.deleteArtworkField()
                        tag.setField(AndroidArtwork().apply {
                            binaryData = artworkUpdate.bytes
                            mimeType = artworkUpdate.mimeType
                            description = ""
                            pictureType = 3 // Front cover
                        })
                    }
                }

                try {
                    audioFile.commit()
                } catch (error: Exception) {
                    throw IllegalStateException("写入 MP3 标签失败", error)
                }

                replaceDocument(context, uri, editedFile, originalFile)
            } finally {
                editedFile.delete()
            }
        }
    }

    private fun replaceDocument(context: Context, uri: Uri, editedFile: File, originalFile: File) {
        val output = openTruncatingOutput(context, uri)

        try {
            output.use { target ->
                FileInputStream(editedFile).use { source -> source.copyTo(target) }
                target.flush()
            }
        } catch (saveError: Exception) {
            val restored = runCatching {
                openTruncatingOutput(context, uri).use { target ->
                    FileInputStream(originalFile).use { source -> source.copyTo(target) }
                    target.flush()
                }
            }.isSuccess
            val message = if (restored) {
                "保存失败，已自动恢复原文件"
            } else {
                "保存失败且无法自动恢复原文件，请使用备份恢复"
            }
            throw IllegalStateException(message, saveError)
        }
    }

    private fun openTruncatingOutput(context: Context, uri: Uri): OutputStream {
        val resolver = context.contentResolver
        runCatching { resolver.openOutputStream(uri, "rwt") }
            .getOrNull()
            ?.let { return it }
        return try {
            resolver.openOutputStream(uri, "wt")
                ?: throw IllegalStateException("无法打开原文件进行写入")
        } catch (error: Exception) {
            throw IllegalStateException("所选位置不允许修改此文件", error)
        }
    }

    fun readArtwork(context: Context, uri: Uri): Pair<ByteArray, String> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_ARTWORK_BYTES) {
                    throw IllegalArgumentException("封面图片不能超过 15 MB")
                }
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        } ?: throw IllegalArgumentException("无法读取所选图片")
        val mimeType = context.contentResolver.getType(uri)
            ?.takeIf { it.startsWith("image/") }
            ?: detectImageMimeType(bytes)
            ?: throw IllegalArgumentException("请选择 JPEG、PNG、GIF 或 WebP 图片")
        return bytes to mimeType
    }

    private inline fun <T> withTemporaryMp3(
        context: Context,
        uri: Uri,
        block: (File) -> T
    ): T {
        val file = File.createTempFile("toolbox_mp3_", ".mp3", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("无法读取所选文件")
            input.use { source ->
                FileOutputStream(file).use { target -> source.copyTo(target) }
            }
            return block(file)
        } catch (error: SecurityException) {
            throw IllegalStateException("没有访问该文件的权限，请重新选择文件", error)
        } finally {
            file.delete()
        }
    }

    private fun queryDocument(context: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "未命名.mp3"
        var size = -1L
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        return name to size
    }

    private fun Tag?.value(key: FieldKey): String =
        this?.let { tag -> runCatching { tag.getFirst(key) }.getOrDefault("") }.orEmpty()

    private fun Tag.put(key: FieldKey, value: String) {
        if (value.isBlank()) deleteField(key) else setField(key, value.trim())
    }

    private fun detectImageMimeType(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 8 && bytes.sliceArray(0..7).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        ) -> "image/png"
        bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII).startsWith("GIF") -> "image/gif"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }
}
