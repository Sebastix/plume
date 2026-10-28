package plume.sebastix.dev

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer

/** Copy compressed audio/video tracks into a new container, without location or other container metadata. */
internal object VideoPrivacy {
    suspend fun clean(input: File, output: File): String {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            val formats = (0 until extractor.trackCount).associateWith(extractor::getTrackFormat)
            val video = formats.values.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: error("Selected file has no video track")
            val webm = video.getString(MediaFormat.KEY_MIME) in setOf("video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9")
            val muxer = MediaMuxer(output.absolutePath, if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                if (!webm) {
                    val metadata = MediaMetadataRetriever()
                    try {
                        metadata.setDataSource(input.absolutePath)
                        metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
                            ?.takeIf { it in listOf(0, 90, 180, 270) }?.let(muxer::setOrientationHint)
                    } finally { metadata.release() }
                }
                val tracks = formats.filterValues {
                    val mime = it.getString(MediaFormat.KEY_MIME).orEmpty()
                    mime.startsWith("video/") || mime.startsWith("audio/")
                }.mapValues { (index, format) ->
                    currentCoroutineContext().ensureActive()
                    extractor.selectTrack(index)
                    muxer.addTrack(format)
                }
                muxer.start()
                // Bound memory even for malformed or unusually large frames.
                val buffer = ByteBuffer.allocateDirect(16 * 1024 * 1024)
                val info = MediaCodec.BufferInfo()
                while (extractor.sampleTrackIndex >= 0) {
                    currentCoroutineContext().ensureActive()
                    require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted videos cannot be uploaded" }
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    require(size <= buffer.capacity()) { "Video frame exceeds upload processing limit" }
                    info.set(0, size, extractor.sampleTime,
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(tracks.getValue(extractor.sampleTrackIndex), buffer, info)
                    require(output.length() <= 250L * 1024 * 1024) { "Video exceeds 250 MB" }
                    extractor.advance()
                }
                muxer.stop()
            } finally { muxer.release() }
            return if (webm) "video/webm" else "video/mp4"
        } finally { extractor.release() }
    }
}
