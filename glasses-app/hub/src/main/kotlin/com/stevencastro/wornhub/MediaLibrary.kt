package com.stevencastro.wornhub

import android.content.ContentResolver
import android.graphics.Bitmap
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Every photo and video on the glasses (whatever app took them), from
 * MediaStore, newest first. Ids are "i<n>" for images and "v<n>" for videos.
 */
class MediaLibrary(private val resolver: ContentResolver) {
    data class Item(val id: String, val path: String, val mime: String, val takenMs: Long, val size: Long,
                    val width: Int, val height: Int, val durationMs: Long)

    fun list(): List<Item> {
        val items = ArrayList<Item>()
        query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "i", false, items)
        query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "v", true, items)
        // The vendor camera saves a "cover_pic_" preview next to each capture; hide those.
        return items.filter { File(it.path).canRead() && !File(it.path).name.startsWith("cover_pic_") }
            .map { if (it.id.startsWith("v") && it.durationMs <= 0) it.copy(durationMs = videoDuration(it)) else it }
            .sortedByDescending { it.takenMs }
    }

    fun find(id: String): Item? = list().firstOrNull { it.id == id }

    fun listJson(): String = JSONObject().put("items", JSONArray().also { arr ->
        list().forEach { m ->
            arr.put(JSONObject()
                .put("id", m.id)
                .put("kind", if (m.id.startsWith("v")) "video" else "photo")
                .put("name", File(m.path).name)
                .put("mime", m.mime)
                .put("takenMs", m.takenMs)
                .put("size", m.size)
                .put("width", m.width)
                .put("height", m.height)
                .put("durationMs", m.durationMs))
        }
    }).toString()

    /** A small JPEG preview, or null. */
    @Suppress("DEPRECATION")
    fun thumbnail(id: String): ByteArray? {
        val n = id.drop(1).toLongOrNull() ?: return null
        val bmp: Bitmap? = when (id.firstOrNull()) {
            'i' -> MediaStore.Images.Thumbnails.getThumbnail(resolver, n, MediaStore.Images.Thumbnails.MINI_KIND, null)
            'v' -> MediaStore.Video.Thumbnails.getThumbnail(resolver, n, MediaStore.Video.Thumbnails.MINI_KIND, null)
            else -> null
        }
        bmp ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private val durations = HashMap<String, Long>()

    /** MediaStore often lacks video durations; read them from the file once. */
    private fun videoDuration(item: Item): Long = synchronized(durations) { durations[item.id] } ?: readDuration(item).also {
        synchronized(durations) { durations[item.id] = it }
    }

    private fun readDuration(item: Item): Long {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(item.path)
            return r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } catch (e: Exception) {
            return 0
        } finally {
            r.release()
        }
    }

    @Suppress("DEPRECATION")
    private fun query(uri: android.net.Uri, prefix: String, video: Boolean, into: MutableList<Item>) {
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT, if (video) MediaStore.Video.VideoColumns.DURATION else MediaStore.MediaColumns._ID,
            if (video) MediaStore.Video.VideoColumns.DATE_TAKEN else MediaStore.Images.ImageColumns.DATE_TAKEN,
        )
        resolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val path = c.getString(1) ?: continue
                val taken = c.getLong(8).takeIf { it > 0 } ?: c.getLong(3) * 1000
                into.add(Item("$prefix$id", path, c.getString(2) ?: if (video) "video/mp4" else "image/jpeg", taken,
                    c.getLong(4), c.getInt(5), c.getInt(6), if (video) c.getLong(7) else 0))
            }
        }
    }

}
