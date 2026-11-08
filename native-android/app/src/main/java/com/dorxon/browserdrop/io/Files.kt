package com.dorxon.browserdrop.io

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.dorxon.browserdrop.proto.FileSink
import com.dorxon.browserdrop.proto.Proto
import com.dorxon.browserdrop.proto.FileSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A file picked or shared into the app, read straight from its provider. */
class UriSource(
    private val context: Context,
    val uri: Uri,
    override val name: String,
    override val size: Long,
    override val mime: String,
) : FileSource {
    override fun open(): InputStream =
        context.contentResolver.openInputStream(uri) ?: error("Can't open $name")

    companion object {
        /** Read name, size and type. Blocking: call off the main thread. */
        fun from(context: Context, uri: Uri): UriSource? {
            // Only content:// from another app. A file:// path would be opened
            // as Ferry itself, so another app could hand over Ferry's own private
            // files (its token) to be sent; and our own package's content is ours.
            if (uri.scheme != "content" || uri.authority?.startsWith(context.packageName) == true) return null
            val cr = context.contentResolver
            var name: String? = null
            var size = -1L
            runCatching {
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                        c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                    }
                }
            }
            // Some providers don't report a size; the descriptor usually knows.
            if (size < 0) size = runCatching { cr.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L
            if (size < 0) return null
            val mime = cr.getType(uri) ?: "application/octet-stream"
            val fallback = "file" + (MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { ".$it" } ?: "")
            return UriSource(context, uri, name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: fallback, size, mime)
        }
    }
}

/**
 * A received file, written into Downloads/Ferry as it arrives. Chunks are
 * placed by offset (positional writes), so nothing is held in memory and the
 * order of arrival doesn't matter. The entry stays "pending" -- invisible to
 * other apps -- until it is complete; a cancelled transfer leaves nothing behind.
 */
class DownloadSink private constructor(
    private val context: Context,
    val uri: Uri,
    private val channel: FileChannel,
    private val closeAll: () -> Unit,
) : FileSink {
    // One writer thread keeps writes ordered and off the engine thread.
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "bd-write").apply { isDaemon = true } }
    @Volatile private var error: Throwable? = null

    override fun write(offset: Long, data: ByteArray, from: Int, len: Int) {
        writer.execute {
            if (error != null) return@execute
            try {
                val buf = ByteBuffer.wrap(data, from, len)
                var pos = offset
                while (buf.hasRemaining()) pos += channel.write(buf, pos)
            } catch (e: Throwable) { error = e }
        }
    }

    override suspend fun finish(): Any = withContext(Dispatchers.IO) {
        writer.shutdown()
        writer.awaitTermination(10, TimeUnit.MINUTES)
        error?.let { abort(); throw it }
        channel.force(false)
        closeAll()
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        uri
    }

    override fun abort() {
        writer.shutdownNow()
        runCatching { closeAll() }
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    companion object {
        const val FOLDER = "Ferry"

        /** Blocking: call off the main thread. */
        fun create(context: Context, name: String, mime: String): DownloadSink {
            val cr = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName(name))
                put(MediaStore.MediaColumns.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Downloads is unavailable")
            val pfd = cr.openFileDescriptor(uri, "rw") ?: run { cr.delete(uri, null, null); error("Can't write to Downloads") }
            val out = FileOutputStream(pfd.fileDescriptor)
            return DownloadSink(context, uri, out.channel) { runCatching { out.close() }; runCatching { pfd.close() } }
        }

        /** A sender can name a file anything; see Proto.cleanName. */
        fun safeName(name: String): String = Proto.cleanName(name)

        /** The type a saved file gets: from its (accepted) name only. */
        fun mimeFor(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
    }
}
