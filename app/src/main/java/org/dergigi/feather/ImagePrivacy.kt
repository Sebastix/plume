package org.dergigi.feather

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream

/** Only sanitized bytes leave the device. Unknown/malformed containers fail closed. */
internal object ImagePrivacy {
    private const val MAX_BYTES = 20L * 1024 * 1024

    fun clean(source: File, destination: File, mime: String): String {
        require(source.length() in 1..MAX_BYTES) { "Image exceeds 20 MB" }
        val bytes = source.readBytes()
        val orientation = if (mime in setOf("image/jpeg", "image/png", "image/webp"))
            ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) else 1
        require(orientation in 0..8) { "Invalid image orientation" }
        val stripped = when (mime) {
            "image/jpeg" -> jpeg(bytes)
            "image/png" -> png(bytes)
            "image/webp" -> webp(bytes)
            "image/gif" -> gif(bytes)
            else -> null
        }
        if (stripped != null && orientation <= 1) {
            destination.writeBytes(stripped)
            return mime
        }
        // Bake EXIF orientation into pixels instead of copying any EXIF to the new file.
        // Animated images with unusual orientation must not silently become a single frame.
        if (orientation > 1) {
            require(!(mime == "image/png" && pngAnimated(bytes)) &&
                !(mime == "image/webp" && webpAnimated(bytes))) { "Cannot safely rotate this animated image" }
        }
        return encodePixels(source, destination, mime, orientation)
    }

    private fun encodePixels(source: File, destination: File, mime: String, orientation: Int): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        val pixels = bounds.outWidth.toLong() * bounds.outHeight
        val budget = minOf(24_000_000L, Runtime.getRuntime().maxMemory() / 20)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && pixels <= budget) { "Image is too large to remove metadata safely" }
        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                require(!info.isAnimated) { "Cannot safely remove metadata from this animated format" }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
                decoder.setOnPartialImageListener { false }
            }
        } else {
            require(mime in setOf("image/jpeg", "image/png", "image/webp")) { "Unsupported image format for metadata removal" }
            val original = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
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
            val png = bitmap.hasAlpha() || mime == "image/png"
            destination.outputStream().use { raw ->
                val bounded = object : FilterOutputStream(raw) {
                    var written = 0L
                    override fun write(b: Int) { checkSize(1); out.write(b) }
                    override fun write(b: ByteArray, off: Int, len: Int) { checkSize(len); out.write(b, off, len) }
                    fun checkSize(n: Int) { written += n; require(written <= MAX_BYTES) { "Sanitized image exceeds 20 MB" } }
                }
                check(bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, bounded)) { "Could not sanitize image" }
            }
            return if (png) "image/png" else "image/jpeg"
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

    private fun pngAnimated(b: ByteArray) = pngChunks(b).any { it.first == "acTL" }
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

    private fun webpAnimated(b: ByteArray) = webpChunks(b).any { it.first == "ANIM" }
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
