package ooo.doo.cnct.ui

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ooo.doo.cnct.CnctAttachmentRules
import ooo.doo.cnct.CnctChatAttachment
import ooo.doo.cnct.CnctChatClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A file the visitor picked — from the photo picker, the camera or the document picker — before
 * anything has been done to it. [open] reads it afresh each time.
 */
internal class PickedFile(
    val name: String,
    val mimeType: String,
    val size: Long,
    val open: () -> InputStream,
) {
    companion object {
        /** What the system says about a picked `content://` file: its name, its size and its type. */
        fun from(resolver: ContentResolver, uri: Uri): PickedFile {
            var name: String? = null
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameAt = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeAt = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameAt >= 0 && !cursor.isNull(nameAt)) name = cursor.getString(nameAt)
                    if (sizeAt >= 0 && !cursor.isNull(sizeAt)) size = cursor.getLong(sizeAt)
                }
            }
            val fileName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            val type = CnctAttachmentRules.normalise(resolver.getType(uri), fileName)
            return PickedFile(fileName, type, size, open = { resolver.openInputStream(uri) ?: error("Cannot read $uri") })
        }
    }
}

/** What goes up: either the picked file untouched, or new bytes made from it. */
internal class PreparedFile(
    val name: String,
    val mimeType: String,
    val size: Long,
    /** Null when the file goes up untouched, from [PickedFile.open]. */
    val bytes: ByteArray?,
)

/**
 * Photos are made fit to send before they go up, and nothing else is touched.
 *
 * **HEIC and HEIF become JPEG** — iPhones and many Android phones take photos in HEIC, and the
 * platform does not keep it. **Anything over 2560 pixels on its long side is brought down to it**
 * (never up): nobody reads a chat photo at more than that, and a phone camera's original is megabytes
 * of somebody's data plan for nothing. JPEG and WebP are written as JPEG at quality 85; PNG stays PNG,
 * because a PNG is usually a screenshot with words in it. GIFs are left alone — they move.
 */
internal object ImagePrep {
    enum class Output { JPEG, PNG }

    /** What to do with an image: its new size and format, or null to send it as it is. */
    data class Plan(val width: Int, val height: Int, val output: Output)

    fun plan(mimeType: String, width: Int, height: Int): Plan? {
        val convert = mimeType in CnctAttachmentRules.CONVERT_TO_JPEG
        val scalable = mimeType == "image/jpeg" || mimeType == "image/png" || mimeType == "image/webp"
        if (!convert && !scalable) return null
        val long = max(width, height)
        val tooBig = long > CnctAttachmentRules.MAX_IMAGE_SIDE
        if (!convert && !tooBig) return null
        if (width <= 0 || height <= 0) return null
        val scale = if (tooBig) CnctAttachmentRules.MAX_IMAGE_SIDE.toDouble() / long else 1.0
        return Plan(
            width = max(1, (width * scale).roundToInt()),
            height = max(1, (height * scale).roundToInt()),
            output = if (mimeType == "image/png") Output.PNG else Output.JPEG,
        )
    }

    /**
     * Made fit to send: [decode] is asked for the bitmap at the planned size. Answers null when the
     * file needs nothing done. [decode] answering null — a phone too old to read HEIC — fails the file
     * rather than sending something the platform will refuse.
     */
    fun prepare(
        name: String,
        mimeType: String,
        width: Int,
        height: Int,
        decode: (targetWidth: Int, targetHeight: Int) -> Bitmap?,
    ): PreparedFile? {
        val plan = plan(mimeType, width, height) ?: return null
        val bitmap = decode(plan.width, plan.height) ?: throw IllegalStateException("$name could not be read")
        val sized = if (bitmap.width > plan.width || bitmap.height > plan.height) {
            Bitmap.createScaledBitmap(bitmap, plan.width, plan.height, true)
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        val (format, type, extension) = when (plan.output) {
            Output.PNG -> Triple(Bitmap.CompressFormat.PNG, "image/png", "png")
            Output.JPEG -> Triple(Bitmap.CompressFormat.JPEG, "image/jpeg", "jpg")
        }
        sized.compress(format, CnctAttachmentRules.JPEG_QUALITY, out)
        val bytes = out.toByteArray()
        val base = name.substringBeforeLast('.', name).ifEmpty { "photo" }
        return PreparedFile("$base.$extension", type, bytes.size.toLong(), bytes)
    }

    /** The picked file made fit to send, decoding with the platform's best decoder for this phone. */
    fun prepare(file: PickedFile): PreparedFile {
        val type = file.mimeType
        if (!type.startsWith("image/")) return PreparedFile(file.name, type, file.size, null)
        val (width, height) = bounds(file) ?: (0 to 0)
        val prepared = prepare(file.name, type, width, height) { w, h -> decode(file, w, h) }
        return prepared ?: PreparedFile(file.name, type, file.size, null)
    }

    private fun bounds(file: PickedFile): Pair<Int, Int>? {
        if (Build.VERSION.SDK_INT >= 28) {
            return try {
                var size: Pair<Int, Int>? = null
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(file.open().use { it.readBytes() }))) { decoder, info, _ ->
                    size = info.size.width to info.size.height
                    // Only the header was wanted. Shrinking the decode keeps this cheap.
                    decoder.setTargetSize(1, 1)
                }
                size
            } catch (_: Exception) {
                null
            }
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.open().use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0) return null
        val rotated = orientation(file).let { it == ExifInterface.ORIENTATION_ROTATE_90 || it == ExifInterface.ORIENTATION_ROTATE_270 }
        return if (rotated) options.outHeight to options.outWidth else options.outWidth to options.outHeight
    }

    private fun decode(file: PickedFile, width: Int, height: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            // Reads HEIC and applies the camera's rotation itself.
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(file.open().use { it.readBytes() }))) { decoder, _, _ ->
                decoder.setTargetSize(width, height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        if (file.mimeType in CnctAttachmentRules.CONVERT_TO_JPEG) return null
        val bitmap = Images.decodeSampled({ file.open() }, max(width, height)) ?: return null
        return rotate(bitmap, orientation(file))
    }

    private fun orientation(file: PickedFile): Int {
        if (file.mimeType != "image/jpeg") return ExifInterface.ORIENTATION_NORMAL
        return try {
            file.open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun rotate(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }
}

/** Decoding without image libraries: read the size, sample down to what will be drawn, decode once. */
internal object Images {
    /** The image in [open], decoded at no more than about [maxSide] pixels on its long side. */
    fun decodeSampled(open: () -> InputStream, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return open().use { BitmapFactory.decodeStream(it, null, options) }
    }

    fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap? = decodeSampled({ bytes.inputStream() }, maxSide)
}

/**
 * The visitor's files and the team's, fetched once each, kept on disk in the app's cache and drawn
 * from memory. Fetched with the session token through the headless client — never by URL, which
 * would need the secret in the address, where it lands in every proxy's access log.
 */
internal class AttachmentStore(context: Context, private val client: CnctChatClient) {
    private val directory = File(context.cacheDir, "cnct-files")
    private val shares = File(directory, "open")
    private val images = object : LruCache<String, ImageBitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }
    private val fetching = ConcurrentHashMap<String, CompletableDeferred<ByteArray>>()

    private fun fileOf(id: String) = File(directory, id.replace(Regex("[^A-Za-z0-9_.-]"), "_"))

    /** A file this device sent, kept so its bubble draws from it rather than downloading it again. */
    suspend fun remember(id: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        fileOf(id).writeBytes(bytes)
    }

    suspend fun remember(id: String, file: PickedFile) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        file.open().use { input -> fileOf(id).outputStream().use { input.copyTo(it) } }
    }

    fun cachedImage(id: String, maxSide: Int): ImageBitmap? = images.get("$id@$maxSide")

    /** The file's bytes: from disk, or downloaded once however many bubbles ask at the same moment. */
    suspend fun bytes(id: String): ByteArray {
        val file = fileOf(id)
        withContext(Dispatchers.IO) { if (file.exists()) return@withContext file.readBytes() else null }?.let { return it }
        val mine = CompletableDeferred<ByteArray>()
        val already = fetching.putIfAbsent(id, mine)
        if (already != null) return already.await()
        return try {
            val bytes = client.downloadAttachment(id)
            withContext(Dispatchers.IO) {
                directory.mkdirs()
                file.writeBytes(bytes)
            }
            mine.complete(bytes)
            bytes
        } catch (error: Throwable) {
            // A failure is not remembered: the next look asks again.
            mine.completeExceptionally(error)
            throw error
        } finally {
            fetching.remove(id)
        }
    }

    /** The image, decoded no larger than [maxSide] pixels, from memory when it has been drawn before. */
    suspend fun image(id: String, maxSide: Int): ImageBitmap? {
        cachedImage(id, maxSide)?.let { return it }
        val bytes = bytes(id)
        val bitmap = withContext(Dispatchers.Default) { Images.decodeSampled(bytes, maxSide) } ?: return null
        return bitmap.asImageBitmap().also { images.put("$id@$maxSide", it) }
    }

    /**
     * A copy under its own name, for another app to open — a PDF viewer shows the name it is given,
     * and "att_8f3a" is not the name somebody was sent.
     */
    suspend fun shareable(attachment: CnctChatAttachment): File {
        val bytes = bytes(attachment.id)
        return withContext(Dispatchers.IO) {
            val folder = File(shares, fileOf(attachment.id).name).apply { mkdirs() }
            val safe = (attachment.filename ?: "file").replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").take(120)
            File(folder, safe.ifBlank { "file" }).apply { writeBytes(bytes) }
        }
    }

    private companion object {
        const val MEMORY_BYTES = 24 * 1024 * 1024
    }
}

/** A flow's own media and the business's logo: public addresses, fetched without the token. */
internal object PublicImages {
    private val client by lazy { OkHttpClient() }
    private val cache = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun cached(url: String): ImageBitmap? = cache.get(url)

    suspend fun load(url: String, maxSide: Int): ImageBitmap? {
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) return null
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val bytes = response.body?.bytes() ?: return@withContext null
                    Images.decodeSampled(bytes, maxSide)?.asImageBitmap()?.also { cache.put(url, it) }
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Hands the chat's files to other apps — the camera, to write a photo into; a PDF viewer, to open an
 * invoice — under its own authority, `<applicationId>.cnct.files`, so it never collides with a
 * FileProvider the app declares for itself.
 */
internal class CnctFileProvider : FileProvider(R.xml.cnct_file_paths) {
    companion object {
        fun authority(context: Context): String = "${context.packageName}.cnct.files"

        /** A fresh file for the camera to write a photo into. */
        fun cameraFile(context: Context): Pair<File, Uri> {
            val folder = File(context.cacheDir, "cnct-camera").apply { mkdirs() }
            val file = File(folder, "photo-${System.currentTimeMillis()}.jpg")
            return file to FileProvider.getUriForFile(context, authority(context), file)
        }

        /**
         * Open a received file in whatever app the phone has for it; with none, offer the share sheet,
         * so a file is never a dead end.
         */
        fun open(context: Context, file: File, mimeType: String) {
            val uri = FileProvider.getUriForFile(context, authority(context), file)
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(view)
            } catch (_: ActivityNotFoundException) {
                val share = Intent(Intent.ACTION_SEND).setType(mimeType).putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(share, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
