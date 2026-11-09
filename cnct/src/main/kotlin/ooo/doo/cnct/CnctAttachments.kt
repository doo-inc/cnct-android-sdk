package ooo.doo.cnct

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.Sink
import okio.buffer
import okio.source
import java.io.InputStream

/**
 * What the platform accepts as a visitor's file, checked here before a byte is sent.
 *
 * The server enforces every one of these and refuses what breaks them. They are repeated here so a
 * UI can say so *before* uploading: somebody on a phone's data plan should not watch a 60 MB video
 * crawl to 100% only to be told it was never going to be accepted.
 */
public object CnctAttachmentRules {
    /** Files per message. */
    public const val MAX_FILES: Int = 10

    /** Bytes per file: 50 MB. */
    public const val MAX_BYTES: Long = 50L * 1024 * 1024

    /**
     * The long side a photo is brought down to before upload. Nobody reads a chat photo at more than
     * this, and a phone camera's 4000-pixel original is several megabytes of mobile data for nothing.
     */
    public const val MAX_IMAGE_SIDE: Int = 2560

    /** The JPEG quality a converted or downscaled photo is written at. */
    public const val JPEG_QUALITY: Int = 85

    /** Every type the platform keeps, by MIME type. */
    public val ACCEPTED_TYPES: Set<String> = setOf(
        // Images.
        "image/jpeg", "image/png", "image/webp", "image/gif",
        // Audio.
        "audio/aac", "audio/mpeg", "audio/ogg", "audio/mp4", "audio/amr",
        // Video.
        "video/mp4", "video/3gpp", "video/quicktime",
        // Documents.
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "text/plain",
        "text/csv",
    )

    /**
     * The types a phone's camera writes that the platform does not keep. **Convert these to JPEG
     * before upload** — iPhones and a good many Android phones take photos in HEIC, and refusing a
     * customer's photo because of a codec they never chose is not an answer. `cnct-ui` does it for
     * you.
     */
    public val CONVERT_TO_JPEG: Set<String> = setOf("image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence")

    private val BY_EXTENSION = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
        "gif" to "image/gif", "heic" to "image/heic", "heif" to "image/heif",
        "aac" to "audio/aac", "mp3" to "audio/mpeg", "ogg" to "audio/ogg", "oga" to "audio/ogg",
        "m4a" to "audio/mp4", "amr" to "audio/amr",
        "mp4" to "video/mp4", "3gp" to "video/3gpp", "mov" to "video/quicktime",
        "pdf" to "application/pdf", "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "txt" to "text/plain", "csv" to "text/csv",
    )

    /**
     * A MIME type in the one spelling the platform uses: lower case, without parameters, `image/jpg`
     * read as `image/jpeg`. Where the type is missing or only says "some bytes", the file name's
     * extension decides — a document picker that reports `application/octet-stream` for a PDF is
     * still sending a PDF.
     */
    public fun normalise(mimeType: String?, filename: String? = null): String {
        val bare = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val fixed = when (bare) {
            "image/jpg", "image/pjpeg" -> "image/jpeg"
            "audio/mp3" -> "audio/mpeg"
            "audio/x-m4a", "audio/m4a" -> "audio/mp4"
            "text/comma-separated-values" -> "text/csv"
            else -> bare
        }
        if (fixed.isNotEmpty() && fixed != "application/octet-stream") return fixed
        val extension = filename?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return BY_EXTENSION[extension] ?: fixed.ifEmpty { "application/octet-stream" }
    }

    /** True when the platform keeps files of this type. */
    public fun isAccepted(mimeType: String?, filename: String? = null): Boolean = normalise(mimeType, filename) in ACCEPTED_TYPES

    /** True for HEIC and HEIF, which are converted to JPEG rather than refused. */
    public fun needsConversion(mimeType: String?, filename: String? = null): Boolean =
        normalise(mimeType, filename) in CONVERT_TO_JPEG

    /**
     * The refusal this file would get, or null when it may go. [CnctErrorCode.TOO_LARGE] for a file
     * over [MAX_BYTES]; [CnctErrorCode.UNSUPPORTED_TYPE] for a type the platform does not keep, HEIC
     * included — convert that first. (The eleventh file of a message is [CnctErrorCode.TOO_MANY],
     * which only the caller holding the other ten can say.)
     */
    public fun check(filename: String, size: Long, mimeType: String?): CnctException? {
        val type = normalise(mimeType, filename)
        if (size > MAX_BYTES) {
            return CnctException("$filename is over 50 MB.", code = CnctErrorCode.TOO_LARGE)
        }
        if (type in CONVERT_TO_JPEG) {
            return CnctException(
                "$filename is a HEIC/HEIF photo, which is not accepted. Convert it to JPEG first.",
                code = CnctErrorCode.UNSUPPORTED_TYPE,
            )
        }
        if (type !in ACCEPTED_TYPES) {
            return CnctException("$filename is a type that cannot be sent ($type).", code = CnctErrorCode.UNSUPPORTED_TYPE)
        }
        if (size < 0) return CnctException("$filename has no size.", code = CnctErrorCode.INVALID)
        return null
    }
}

/**
 * A request body that reports how much of itself has been written, as a fraction from 0 to 1.
 *
 * The report comes from OkHttp's writer thread as the bytes go into the socket, so it is the real
 * progress of the upload rather than an estimate. A body OkHttp writes twice — a retried connection,
 * a logging interceptor — reports twice from zero, which is what happened.
 */
internal class CountingRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (Float) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun isOneShot(): Boolean = delegate.isOneShot()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        var written = 0L
        var reported = -1
        onProgress(0f)
        val counting = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                // In chunks: a buffered writer hands over everything it holds in one call, and the
                // socket below blocks as it fills — so counting per chunk is counting what has left.
                var left = byteCount
                while (left > 0) {
                    val chunk = minOf(left, CHUNK)
                    super.write(source, chunk)
                    left -= chunk
                    written += chunk
                    if (total > 0) {
                        // Whole percents only: a 50 MB file is six thousand chunks, and a UI redrawing
                        // a progress ring six thousand times is a UI that stutters.
                        val percent = ((written * 100) / total).toInt().coerceAtMost(100)
                        if (percent != reported) {
                            reported = percent
                            onProgress(percent / 100f)
                        }
                    }
                }
            }
        }
        val buffered = (counting as Sink).buffer()
        delegate.writeTo(buffered)
        buffered.flush()
        if (reported != 100) onProgress(1f)
    }

    private companion object {
        const val CHUNK = 8L * 1024
    }
}

/** A file's bytes from a stream, opened afresh each time OkHttp writes it. */
internal class StreamRequestBody(
    private val type: MediaType?,
    private val length: Long,
    private val open: () -> InputStream,
) : RequestBody() {
    override fun contentType(): MediaType? = type

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        open().source().use { sink.writeAll(it) }
    }

    companion object {
        fun of(mimeType: String, length: Long, open: () -> InputStream): StreamRequestBody =
            StreamRequestBody(mimeType.toMediaTypeOrNull(), length, open)
    }
}
