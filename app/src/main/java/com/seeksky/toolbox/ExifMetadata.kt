package com.seeksky.toolbox

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

data class ExifEditable(
    val description: String = "",
    val userComment: String = "",
    val artist: String = "",
    val copyright: String = "",
    val make: String = "",
    val model: String = "",
    val software: String = "",
    val dateTimeOriginal: String = "",
    val latitude: String = "",
    val longitude: String = ""
)

data class ExifItem(
    val label: String,
    val value: String
)

data class ExifImage(
    val displayName: String,
    val mimeType: String,
    val byteSize: Long?,
    val width: Int?,
    val height: Int?,
    val preview: Bitmap?,
    val editable: ExifEditable,
    val items: List<ExifItem>
)

object ExifMetadata {
    private val editableTags = listOf(
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_DATETIME_ORIGINAL
    )

    private val visibleTags = listOf(
        "图片描述" to ExifInterface.TAG_IMAGE_DESCRIPTION,
        "用户备注" to ExifInterface.TAG_USER_COMMENT,
        "作者" to ExifInterface.TAG_ARTIST,
        "版权" to ExifInterface.TAG_COPYRIGHT,
        "相机制造商" to ExifInterface.TAG_MAKE,
        "相机型号" to ExifInterface.TAG_MODEL,
        "处理软件" to ExifInterface.TAG_SOFTWARE,
        "修改时间" to ExifInterface.TAG_DATETIME,
        "拍摄时间" to ExifInterface.TAG_DATETIME_ORIGINAL,
        "数字化时间" to ExifInterface.TAG_DATETIME_DIGITIZED,
        "方向" to ExifInterface.TAG_ORIENTATION,
        "EXIF 图像宽度" to ExifInterface.TAG_PIXEL_X_DIMENSION,
        "EXIF 图像高度" to ExifInterface.TAG_PIXEL_Y_DIMENSION,
        "曝光时间" to ExifInterface.TAG_EXPOSURE_TIME,
        "光圈值" to ExifInterface.TAG_F_NUMBER,
        "曝光程序" to ExifInterface.TAG_EXPOSURE_PROGRAM,
        "ISO 感光度" to ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        "曝光补偿" to ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        "测光模式" to ExifInterface.TAG_METERING_MODE,
        "闪光灯" to ExifInterface.TAG_FLASH,
        "焦距" to ExifInterface.TAG_FOCAL_LENGTH,
        "35mm 等效焦距" to ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        "白平衡" to ExifInterface.TAG_WHITE_BALANCE,
        "色彩空间" to ExifInterface.TAG_COLOR_SPACE,
        "镜头制造商" to ExifInterface.TAG_LENS_MAKE,
        "镜头型号" to ExifInterface.TAG_LENS_MODEL,
        "GPS 海拔" to ExifInterface.TAG_GPS_ALTITUDE,
        "GPS 日期" to ExifInterface.TAG_GPS_DATESTAMP,
        "GPS 时间" to ExifInterface.TAG_GPS_TIMESTAMP,
        "GPS 定位方式" to ExifInterface.TAG_GPS_PROCESSING_METHOD
    )

    fun read(context: Context, uri: Uri): Result<ExifImage> = runCatching {
        val resolver = context.contentResolver
        val fileInfo = queryFileInfo(resolver, uri)
        val exif = resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            ExifInterface(descriptor.fileDescriptor)
        } ?: error("无法打开所选图片")

        val latLong = exif.latLong
        val editable = ExifEditable(
            description = exif.attribute(ExifInterface.TAG_IMAGE_DESCRIPTION),
            userComment = exif.attribute(ExifInterface.TAG_USER_COMMENT),
            artist = exif.attribute(ExifInterface.TAG_ARTIST),
            copyright = exif.attribute(ExifInterface.TAG_COPYRIGHT),
            make = exif.attribute(ExifInterface.TAG_MAKE),
            model = exif.attribute(ExifInterface.TAG_MODEL),
            software = exif.attribute(ExifInterface.TAG_SOFTWARE),
            dateTimeOriginal = exif.attribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            latitude = latLong?.getOrNull(0)?.toCoordinate().orEmpty(),
            longitude = latLong?.getOrNull(1)?.toCoordinate().orEmpty()
        )

        val items = buildList {
            if (latLong != null && latLong.size >= 2) {
                add(ExifItem("GPS 坐标", "${latLong[0].toCoordinate()}, ${latLong[1].toCoordinate()}"))
            }
            visibleTags.forEach { (label, tag) ->
                exif.getAttribute(tag)
                    ?.takeIf(String::isNotBlank)
                    ?.takeUnless { tag == ExifInterface.TAG_ORIENTATION && it == "0" }
                    ?.let { rawValue ->
                    val value = if (tag == ExifInterface.TAG_ORIENTATION) {
                        orientationLabel(rawValue)
                    } else {
                        rawValue
                    }
                    add(ExifItem(label, value))
                    }
            }
        }

        val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            .takeIf { it > 0 }
            ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0).takeIf { it > 0 }
        val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            .takeIf { it > 0 }
            ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0).takeIf { it > 0 }

        ExifImage(
            displayName = fileInfo.first ?: uri.lastPathSegment ?: "未命名图片",
            mimeType = resolver.getType(uri) ?: "未知",
            byteSize = fileInfo.second,
            width = width,
            height = height,
            preview = decodePreview(resolver, uri),
            editable = editable,
            items = items
        )
    }

    fun save(context: Context, uri: Uri, value: ExifEditable): Result<Unit> = runCatching {
        validate(value)?.let(::error)
        context.contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
            val exif = ExifInterface(descriptor.fileDescriptor)
            val values = listOf(
                value.description,
                value.userComment,
                value.artist,
                value.copyright,
                value.make,
                value.model,
                value.software,
                value.dateTimeOriginal
            )
            editableTags.zip(values).forEach { (tag, text) ->
                exif.setAttribute(tag, text.trim().ifBlank { null })
            }

            val latitude = value.latitude.trim()
            val longitude = value.longitude.trim()
            if (latitude.isBlank() && longitude.isBlank()) {
                clearCoordinates(exif)
            } else {
                exif.setLatLong(latitude.toDouble(), longitude.toDouble())
            }
            exif.saveAttributes()
        } ?: error("图片提供方不允许写入此文件")
    }

    fun validate(value: ExifEditable): String? {
        val dateTime = value.dateTimeOriginal.trim()
        if (dateTime.isNotEmpty() && !isExifDateTime(dateTime)) {
            return "拍摄时间格式应为 yyyy:MM:dd HH:mm:ss"
        }

        val latitudeText = value.latitude.trim()
        val longitudeText = value.longitude.trim()
        if (latitudeText.isBlank() xor longitudeText.isBlank()) {
            return "纬度和经度需要同时填写，或同时留空"
        }
        if (latitudeText.isNotBlank()) {
            val latitude = latitudeText.toDoubleOrNull()
                ?: return "纬度必须是 -90 到 90 之间的数字"
            val longitude = longitudeText.toDoubleOrNull()
                ?: return "经度必须是 -180 到 180 之间的数字"
            if (!latitude.isFinite() || latitude !in -90.0..90.0) {
                return "纬度必须是 -90 到 90 之间的数字"
            }
            if (!longitude.isFinite() || longitude !in -180.0..180.0) {
                return "经度必须是 -180 到 180 之间的数字"
            }
        }
        return null
    }

    private fun ExifInterface.attribute(tag: String): String = getAttribute(tag).orEmpty()

    private fun clearCoordinates(exif: ExifInterface) {
        listOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF
        ).forEach { exif.setAttribute(it, null) }
    }

    private fun isExifDateTime(value: String): Boolean {
        val formatter = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
            isLenient = false
        }
        val position = ParsePosition(0)
        return formatter.parse(value, position) != null && position.index == value.length
    }

    private fun queryFileInfo(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> {
        return resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null to null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = nameIndex.takeIf { it >= 0 }?.let(cursor::getString)
            val size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getLong)
                ?.takeIf { it >= 0 }
            name to size
        } ?: (null to null)
    }

    private fun decodePreview(resolver: ContentResolver, uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.loadThumbnail(uri, Size(1200, 1200), null)
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > 1200 || bounds.outHeight / sampleSize > 1200) {
                sampleSize *= 2
            }
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = sampleSize }
                )
            }
        }
    }.getOrNull()

    private fun Double.toCoordinate(): String = String.format(Locale.US, "%.6f", this)

    private fun orientationLabel(rawValue: String): String {
        val label = when (rawValue.toIntOrNull()) {
            ExifInterface.ORIENTATION_NORMAL -> "正常"
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> "水平翻转"
            ExifInterface.ORIENTATION_ROTATE_180 -> "旋转 180°"
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> "垂直翻转"
            ExifInterface.ORIENTATION_TRANSPOSE -> "转置"
            ExifInterface.ORIENTATION_ROTATE_90 -> "旋转 90°"
            ExifInterface.ORIENTATION_TRANSVERSE -> "横向转置"
            ExifInterface.ORIENTATION_ROTATE_270 -> "旋转 270°"
            else -> "未知"
        }
        return "$label（$rawValue）"
    }
}
