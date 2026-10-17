package org.dergigi.feather

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/** Only sanitized bytes leave the device. Unknown/malformed containers fail closed. */
internal object ImagePrivacy {
    private const val MAX_BYTES = 20L * 1024 * 1024
    private const val COMPRESS_ABOVE = 2L * 1024 * 1024
    private const val PHOTO_EDGE = 2560

    data class Result(val mime: String, val compressed: Boolean = false)

    fun clean(source: File, destination: File, mime: String, preferLossless: Boolean = false, onCompress: () -> Unit = {}): Result {
        require(source.length() in 1..250L * 1024 * 1024) { "Image exceeds 250 MB" }
        val orientation = if (mime in setOf("image/jpeg", "image/png", "image/webp"))
            ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) else 1
        require(orientation in 0..8) { "Invalid image orientation" }
        val container = inspect(source, mime)
        require(!container.animated || orientation <= 1) { "Cannot safely rotate this animated image" }
        // Preserve the sanitized original when it already fits, and compare it with any
        // optional compression result. Never read an oversized source into a byte array.
        val originalMime = if (source.length() <= MAX_BYTES && orientation <= 1 &&
            mime in setOf("image/jpeg", "image/png", "image/webp", "image/gif")) {
            val bytes = source.readBytes()
            val stripped = when (mime) {
                "image/jpeg" -> jpeg(bytes)
                "image/png" -> png(bytes)
                "image/webp" -> webp(bytes)
                else -> gif(bytes)
            }
            destination.writeBytes(stripped)
            mime
        } else null
        if (container.animated) {
            require(originalMime != null) { "Animated image exceeds 20 MB" }
            return Result(originalMime)
        }
        val optimize = source.length() > COMPRESS_ABOVE
        if (originalMime != null && (!optimize || destination.length() <= COMPRESS_ABOVE)) return Result(originalMime)
        val candidate = File.createTempFile("blossom-", ".image", destination.parentFile)
        try {
            if (optimize) onCompress()
            val lossless = preferLossless || container.lossless || mime !in setOf("image/jpeg", "image/webp", "image/heic", "image/heif", "image/avif")
            val encodedMime = try {
                encodePixels(source, candidate, mime, orientation, optimize, lossless)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // Optional optimization must not reject an already sanitized, uploadable image.
                if (originalMime != null) return Result(originalMime)
                throw e
            }
            if (originalMime != null && destination.length() <= candidate.length()) return Result(originalMime)
            candidate.copyTo(destination, overwrite = true)
            return Result(encodedMime, compressed = optimize && candidate.length() < source.length())
        } finally { candidate.delete() }
    }

    private data class Container(val animated: Boolean = false, val lossless: Boolean = false)

    /** Read only chunk headers, including on Android versions that cannot decode APNG animation. */
    private fun inspect(source: File, mime: String): Container {
        if (mime == "image/gif") return Container(animated = true, lossless = true)
        if (mime !in setOf("image/png", "image/webp")) return Container()
        return RandomAccessFile(source, "r").use { input ->
            fun text(n: Int): String = ByteArray(n).also(input::readFully).toString(Charsets.US_ASCII)
            fun uint(little: Boolean = false): Long = (if (little) Integer.reverseBytes(input.readInt()) else input.readInt()).toLong() and 0xffffffffL
            val png = mime == "image/png"
            val end = if (png) {
                val signature = ByteArray(8).also(input::readFully)
                require(signature.contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))) { "Invalid PNG" }
                input.length()
            } else {
                require(text(4) == "RIFF") { "Invalid WebP" }
                val end = uint(true) + 8
                require(text(4) == "WEBP" && end in 12..input.length()) { "Invalid WebP" }
                end
            }
            var animated = false
            var lossless = png
            var chunks = 0
            while (input.filePointer < end) {
                require(++chunks <= 16384 && end - input.filePointer >= (if (png) 12 else 8)) { "Invalid image chunks" }
                val length: Long
                val type: String
                if (png) { length = uint(); type = text(4) } else { type = text(4); length = uint(true) }
                val next = input.filePointer + length + (if (png) 4L else (length and 1L))
                require(next <= end) { "Truncated image chunk" }
                if (png && type == "acTL" || !png && type in setOf("ANIM", "ANMF")) animated = true
                if (!png && type in setOf("VP8L", "ALPH")) lossless = true
                if (!png && type == "VP8X") {
                    require(length == 10L) { "Invalid WebP header" }
                    val flags = input.readUnsignedByte()
                    animated = animated || (flags and 2) != 0
                    lossless = lossless || (flags and 16) != 0 // Preserve alpha without JPEG conversion.
                }
                input.seek(next)
                if (png && type == "IEND") return@use Container(animated, lossless)
            }
            require(!png && input.filePointer == end) { "Missing image end marker" }
            Container(animated, lossless)
        }
    }

    private fun encodePixels(source: File, destination: File, mime: String, orientation: Int, optimize: Boolean, lossless: Boolean): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid image dimensions" }
        val edge = maxOf(bounds.outWidth, bounds.outHeight)
        val scale = if (optimize && !lossless) minOf(1.0, PHOTO_EDGE.toDouble() / edge) else 1.0
        val width = (bounds.outWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (bounds.outHeight * scale).roundToInt().coerceAtLeast(1)
        val pixels = width.toLong() * height
        val budget = minOf(24_000_000L, Runtime.getRuntime().maxMemory() / 20)
        require(pixels <= budget) { "Image is too large to process safely without losing detail" }
        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                require(!info.isAnimated) { "Cannot safely remove metadata from this animated format" }
                // ImageDecoder's dimensions and output already account for EXIF rotation.
                val ratio = if (optimize && !lossless) minOf(1.0, PHOTO_EDGE.toDouble() / maxOf(info.size.width, info.size.height)) else 1.0
                val targetWidth = (info.size.width * ratio).roundToInt().coerceAtLeast(1)
                val targetHeight = (info.size.height * ratio).roundToInt().coerceAtLeast(1)
                require(targetWidth.toLong() * targetHeight <= budget) { "Image is too large to process safely without losing detail" }
                decoder.setTargetSize(targetWidth, targetHeight)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
                decoder.setOnPartialImageListener { false }
            }
        } else {
            require(mime in setOf("image/jpeg", "image/png", "image/webp")) { "Unsupported image format for metadata removal" }
            // Round sampling up so the intermediate bitmap cannot exceed the target budget.
            var sample = 1
            while ((bounds.outWidth.toLong() + sample - 1) / sample > width || (bounds.outHeight.toLong() + sample - 1) / sample > height) sample *= 2
            val original = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sample
            }) ?: error("Could not decode image")
            val matrix = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }
                    6 -> setRotate(90f)
                    7 -> { setRotate(270f); postScale(-1f, 1f) }
                    8 -> setRotate(270f)
                }
            }
            try {
                Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true).also {
                    if (it !== original) original.recycle()
                }
            } catch (e: Exception) { original.recycle(); throw e }
        }
        try {
            val keepLossless = lossless || bitmap.hasAlpha()
            val format = if (keepLossless) {
                if (optimize && Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG
            } else Bitmap.CompressFormat.JPEG
            destination.outputStream().use { raw ->
                val bounded = object : FilterOutputStream(raw) {
                    var written = 0L
                    override fun write(b: Int) { checkSize(1); out.write(b) }
                    override fun write(b: ByteArray, off: Int, len: Int) { checkSize(len); out.write(b, off, len) }
                    fun checkSize(n: Int) { written += n; require(written <= MAX_BYTES) { "Sanitized image exceeds 20 MB" } }
                }
                check(bitmap.compress(format, if (keepLossless) 50 else if (optimize) 85 else 95, bounded)) { "Could not sanitize image" }
            }
            return if (!keepLossless) "image/jpeg"
                else if (optimize && Build.VERSION.SDK_INT >= 30) "image/webp" else "image/png"
        } finally { bitmap.recycle() }
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 255
    private fun int32(b: ByteArray, i: Int, little: Boolean): Int {
        require(i >= 0 && i <= b.size - 4) { "Truncated image" }
        var value = 0L
        for (n in 0..3) value = value or (u8(b, i + n).toLong() shl (8 * if (little) n else 3 - n))
        require(value <= Int.MAX_VALUE) { "Invalid image chunk size" }
        return value.toInt()
    }
    private fun text(b: ByteArray, start: Int, length: Int) = String(b, start, length, Charsets.US_ASCII)
    private fun copy(out: OutputStream, b: ByteArray, from: Int, to: Int) {
        require(from >= 0 && to >= from && to <= b.size) { "Truncated image" }
        out.write(b, from, to - from)
    }

    /** Strip all APP/COM blocks (EXIF, XMP, IPTC, thumbnails, camera metadata) and trailing data. */
    private fun jpeg(b: ByteArray): ByteArray {
        require(b.size >= 4 && u8(b, 0) == 255 && u8(b, 1) == 216) { "Invalid JPEG" }
        val out = ByteArrayOutputStream(b.size)
        copy(out, b, 0, 2)
        var p = 2
        var scan = false
        while (p < b.size) {
            if (scan) {
                val start = p
                while (p < b.size) {
                    if (u8(b, p) != 255) { p++; continue }
                    require(p + 1 < b.size) { "Truncated JPEG scan" }
                    val next = u8(b, p + 1)
                    if (next == 0 || next in 208..215) { p += 2; continue }
                    break
                }
                copy(out, b, start, p)
                scan = false
            }
            require(p < b.size && u8(b, p) == 255) { "Invalid JPEG marker" }
            while (p < b.size && u8(b, p) == 255) p++
            require(p < b.size) { "Truncated JPEG marker" }
            val marker = u8(b, p++)
            if (marker == 217) { out.write(255); out.write(marker); return out.toByteArray() }
            require(marker != 0 && marker != 216 && marker !in 208..215 && marker != 1 && p + 2 <= b.size) { "Invalid JPEG segment" }
            val length = (u8(b, p) shl 8) or u8(b, p + 1)
            require(length >= 2 && length <= b.size - p) { "Truncated JPEG segment" }
            // Adobe's fixed color-transform block affects rendering; no free-form fields.
            val adobe = marker == 238 && length == 14 && text(b, p + 2, 5) == "Adobe"
            if (marker !in 224..239 && marker != 254 || adobe) {
                out.write(255); out.write(marker); copy(out, b, p, p + length)
            }
            p += length
            scan = marker == 218
        }
        error("Missing JPEG end marker")
    }

    private fun pngChunks(b: ByteArray): List<Pair<String, IntRange>> {
        require(b.size >= 8 && b.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))) { "Invalid PNG" }
        val chunks = mutableListOf<Pair<String, IntRange>>()
        var p = 8
        while (p <= b.size - 12) {
            val length = int32(b, p, false)
            require(length <= b.size - p - 12) { "Truncated PNG" }
            val type = text(b, p + 4, 4)
            require(chunks.size < 16384) { "Too many PNG chunks" }
            chunks += type to (p until p + length + 12)
            p += length + 12
            if (type == "IEND") return chunks
        }
        error("Missing PNG end marker")
    }
    private fun png(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        copy(out, b, 0, 8)
        // Rendering/animation only: exclude EXIF, XMP, text, dates and arbitrary extensions.
        val keep = setOf("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "sBIT", "acTL", "fcTL", "fdAT")
        for ((type, range) in pngChunks(b)) if (type in keep) copy(out, b, range.first, range.last + 1)
        return out.toByteArray()
    }

    private fun webpChunks(b: ByteArray): List<Pair<String, IntRange>> {
        require(b.size >= 12 && text(b, 0, 4) == "RIFF" && text(b, 8, 4) == "WEBP") { "Invalid WebP" }
        val length = int32(b, 4, true)
        require(length >= 4 && length <= b.size - 8) { "Truncated WebP" }
        val end = length + 8
        val chunks = mutableListOf<Pair<String, IntRange>>()
        var p = 12
        while (p <= end - 8) {
            val size = int32(b, p + 4, true)
            require(size <= end - p - 8 && size + (size and 1) <= end - p - 8) { "Truncated WebP chunk" }
            require(chunks.size < 16384) { "Too many WebP chunks" }
            chunks += text(b, p, 4) to (p until p + 8 + size + (size and 1))
            p += 8 + size + (size and 1)
        }
        require(p == end) { "Invalid WebP chunks" }
        return chunks
    }
    private fun webp(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        copy(out, b, 0, 12)
        for ((type, range) in webpChunks(b)) if (type in setOf("VP8X", "VP8 ", "VP8L", "ALPH", "ANIM", "ANMF")) {
            val chunk = b.copyOfRange(range.first, range.last + 1)
            if (type == "VP8X") { require(chunk.size == 18); chunk[8] = (u8(chunk, 8) and 0xD3).toByte() } // clear ICC/EXIF/XMP flags
            // ANMF contains frame image chunks, not top-level metadata.
            if (type == "ANMF") {
                require(chunk.size >= 24) { "Invalid WebP frame" }
                var p = 24
                while (p <= chunk.size - 8) {
                    require(text(chunk, p, 4) in setOf("ALPH", "VP8 ", "VP8L")) { "Unsupported WebP frame chunk" }
                    val size = int32(chunk, p + 4, true)
                    require(size <= chunk.size - p - 8) { "Truncated WebP frame" }
                    p += 8 + size + (size and 1)
                }
                require(p == chunk.size) { "Invalid WebP frame" }
            }
            out.write(chunk)
        }
        val result = out.toByteArray()
        val size = result.size - 8
        for (i in 0..3) result[4 + i] = (size ushr (8 * i)).toByte()
        return result
    }

    private fun gif(b: ByteArray): ByteArray {
        require(b.size >= 13 && text(b, 0, 6) in setOf("GIF87a", "GIF89a")) { "Invalid GIF" }
        val out = ByteArrayOutputStream(b.size)
        var p = 13 + if (u8(b, 10) and 128 != 0) 3 * (1 shl ((u8(b, 10) and 7) + 1)) else 0
        copy(out, b, 0, p)
        fun blocks(): Int {
            while (true) {
                require(p < b.size) { "Truncated GIF" }
                val size = u8(b, p++)
                require(size <= b.size - p) { "Truncated GIF block" }
                p += size
                if (size == 0) return p
            }
        }
        while (p < b.size) {
            val start = p
            when (u8(b, p++)) {
                59 -> { out.write(59); return out.toByteArray() }
                44 -> {
                    require(p + 9 <= b.size) { "Truncated GIF frame" }
                    val flags = u8(b, p + 8)
                    p += 9
                    if (flags and 128 != 0) p += 3 * (1 shl ((flags and 7) + 1))
                    p++ // LZW code size
                    blocks(); copy(out, b, start, p)
                }
                33 -> {
                    require(p < b.size) { "Truncated GIF extension" }
                    val type = u8(b, p++)
                    val header = p
                    blocks()
                    if (type == 249) { require(p - header == 6 && u8(b, header) == 4); copy(out, b, start, p) }
                    // Preserve only the fixed, standard animation loop extension.
                    if (type == 255 && p - header == 17 && u8(b, header) == 11 &&
                        text(b, header + 1, 11) in setOf("NETSCAPE2.0", "ANIMEXTS1.0") &&
                        u8(b, header + 12) == 3 && u8(b, header + 13) == 1) copy(out, b, start, p)
                }
                else -> error("Invalid GIF block")
            }
        }
        error("Missing GIF end marker")
    }
}
