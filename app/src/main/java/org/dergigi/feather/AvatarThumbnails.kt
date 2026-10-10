package org.dergigi.feather

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import coil3.BitmapImage
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.maxBitmapSize
import coil3.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** Small local derivatives share the image LRU and storage budget with their originals. */
class AvatarThumbnails(private val disk: DiskCache) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): coil3.request.ImageResult {
        val request = chain.request
        val originalKey = request.diskCacheKey?.takeIf { ":avatar:" in it } ?: return chain.proceed()
        val key = "$originalKey:thumbnail-256-v1"
        val sized = request.newBuilder().size(256, 256).maxBitmapSize(Size(512, 512)).allowHardware(false).memoryCacheKey(key).build()
        val snapshot = withContext(Dispatchers.IO) { runCatching { disk.openSnapshot(key) }.getOrNull() }
        if (snapshot != null) {
            try {
                val cached = chain.withRequest(sized.newBuilder().data(snapshot.data.toFile()).diskCachePolicy(CachePolicy.DISABLED).build())
                    .withSize(Size(256, 256)).proceed()
                if (cached is SuccessResult) return cached
            } finally { snapshot.close() }
            withContext(Dispatchers.IO) { runCatching { disk.remove(key) } }
        }
        val result = chain.withRequest(sized).withSize(Size(256, 256)).proceed()
        if (result is SuccessResult) withContext(Dispatchers.IO) {
            val bitmap = (result.image as? BitmapImage)?.bitmap ?: return@withContext
            val editor = runCatching { disk.openEditor(key) }.getOrNull() ?: return@withContext
            try {
                val edge = minOf(bitmap.width, bitmap.height)
                val left = (bitmap.width - edge) / 2
                val top = (bitmap.height - edge) / 2
                val thumbnail = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(thumbnail).drawBitmap(bitmap, Rect(left, top, left + edge, top + edge), Rect(0, 0, 256, 256), Paint(Paint.FILTER_BITMAP_FLAG))
                    editor.metadata.toFile().writeText("")
                    editor.data.toFile().outputStream().use { check(thumbnail.compress(Bitmap.CompressFormat.WEBP, 85, it)) }
                    editor.commit()
                } finally { thumbnail.recycle() }
            } catch (e: Exception) {
                runCatching { editor.abort() }
                if (e is CancellationException) throw e
                // A cache write must never prevent showing the already decoded original.
            }
        }
        return result
    }
}
