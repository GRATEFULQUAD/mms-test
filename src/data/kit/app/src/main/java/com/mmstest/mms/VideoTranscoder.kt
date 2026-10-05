package com.mmstest.mms

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Intelligently transcode videos for MMS using Media3 Transformer.
 *
 * Strategy:
 *  1. Extract duration & dimensions via [MediaMetadataRetriever].
 *  2. Calculate exact target video bitrate required to fit [targetBytes] (leaving a 8% safety margin).
 *  3. Calculate Bits-Per-Pixel (BPP) to pick the highest resolution that won't cause blockiness (target BPP >= 0.05).
 *  4. Configure Media3 [DefaultEncoderFactory] with explicit [VideoEncoderSettings] matching [targetVideoBitrate].
 *  5. If output slightly exceeds [targetBytes], perform a second fine-tuning pass with bitrate adjusted by the exact ratio.
 */
class VideoTranscoder {

    data class Result(val file: File, val height: Int)

    private data class VideoMetadata(
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val displayWidth: Int,
        val displayHeight: Int
    )

    @OptIn(UnstableApi::class)
    fun transcode(context: Context, input: File, targetBytes: Int): Result? {
        val meta = extractMetadata(input)
        if (meta == null || meta.durationMs <= 0) {
            DiagnosticLogger.log("[TRANSCODE] Could not read video metadata; falling back to default transcode strategy.")
            return fallbackTranscode(context, input, targetBytes)
        }

        val durationSec = maxOf(1.0, meta.durationMs / 1000.0)
        // 92% usable byte budget (leaves 8% margin for MP4 container + MMS PDU header overhead)
        val usableBytes = (targetBytes * 0.92).toLong()
        val totalBitrateBps = ((usableBytes * 8.0) / durationSec).toInt()
        val audioBitrateBps = 64_000 // 64 kbps AAC
        var targetVideoBitrate = maxOf(100_000, totalBitrateBps - audioBitrateBps)

        DiagnosticLogger.log("[TRANSCODE] Input metadata: duration=${String.format(Locale.US, "%.2f", durationSec)}s, res=${meta.displayWidth}x${meta.displayHeight}")
        DiagnosticLogger.log("[TRANSCODE] Target ceiling=$targetBytes B, usableBytes=$usableBytes B -> targetVideoBitrate=${targetVideoBitrate / 1000} kbps")

        // Select optimal target height based on Bits-Per-Pixel (BPP)
        val candidateHeights = listOf(meta.displayHeight, 1080, 720, 540, 480, 360, 240)
            .filter { it <= meta.displayHeight }
            .distinct()
            .sortedDescending()

        var chosenHeight = candidateHeights.last()
        for (h in candidateHeights) {
            val aspectRatio = meta.displayWidth.toDouble() / meta.displayHeight.toDouble()
            val w = maxOf(1, (h * aspectRatio).toInt())
            val bpp = targetVideoBitrate.toDouble() / (w * h * 30.0)
            if (bpp >= 0.05 || h <= 360) {
                chosenHeight = h
                DiagnosticLogger.log("[TRANSCODE] Selected resolution height=$h px (estimated BPP=${String.format(Locale.US, "%.3f", bpp)})")
                break
            }
        }

        // Pass 1: Transcode with calculated target bitrate & height
        DiagnosticLogger.log("[TRANSCODE] Pass 1: height=$chosenHeight px, targetBitrate=${targetVideoBitrate / 1000} kbps")
        val outFile1 = File(context.cacheDir, "transcoded_p1_${chosenHeight}_${System.currentTimeMillis()}.mp4")
        if (!runTranscode(context, input, outFile1, chosenHeight, targetVideoBitrate)) {
            DiagnosticLogger.log("[TRANSCODE] Pass 1 failed.")
            return null
        }

        val size1 = outFile1.length()
        DiagnosticLogger.log("[TRANSCODE] Pass 1 produced $size1 bytes (ceiling: $targetBytes B)")
        if (size1 <= targetBytes) {
            DiagnosticLogger.log("[TRANSCODE] SUCCESS: Pass 1 fits within ceiling!")
            return Result(outFile1, chosenHeight)
        }

        // Pass 2 (Fine-tuning): If Pass 1 exceeded targetBytes due to VBR fluctuations, adjust bitrate proportionally
        val overageRatio = size1.toDouble() / targetBytes.toDouble()
        targetVideoBitrate = maxOf(80_000, (targetVideoBitrate / (overageRatio * 1.05)).toInt())
        DiagnosticLogger.log("[TRANSCODE] Pass 1 exceeded ceiling by ratio ${String.format(Locale.US, "%.2f", overageRatio)}x.")
        DiagnosticLogger.log("[TRANSCODE] Pass 2 (Fine-Tuning): height=$chosenHeight px, adjustedBitrate=${targetVideoBitrate / 1000} kbps")

        val outFile2 = File(context.cacheDir, "transcoded_p2_${chosenHeight}_${System.currentTimeMillis()}.mp4")
        if (runTranscode(context, input, outFile2, chosenHeight, targetVideoBitrate)) {
            val size2 = outFile2.length()
            DiagnosticLogger.log("[TRANSCODE] Pass 2 produced $size2 bytes")
            if (size2 <= targetBytes) {
                DiagnosticLogger.log("[TRANSCODE] SUCCESS: Pass 2 fine-tuning fits within ceiling!")
                return Result(outFile2, chosenHeight)
            }
            if (size2 < size1) {
                DiagnosticLogger.log("[TRANSCODE] Pass 2 smaller than Pass 1; using Pass 2 ($size2 bytes).")
                return Result(outFile2, chosenHeight)
            }
        }

        return Result(outFile1, chosenHeight)
    }

    private fun extractMetadata(input: File): VideoMetadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(input.absolutePath)
            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val dw = if (rot == 90 || rot == 270) h else w
            val dh = if (rot == 90 || rot == 270) w else h
            VideoMetadata(durationMs = dur, width = w, height = h, displayWidth = dw, displayHeight = dh)
        } catch (e: Exception) {
            DiagnosticLogger.log("[TRANSCODE] Metadata extraction failed: ${e.message}")
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    @OptIn(UnstableApi::class)
    private fun fallbackTranscode(context: Context, input: File, targetBytes: Int): Result? {
        val heights = listOf(720, 480, 360, 240)
        for (h in heights) {
            val outFile = File(context.cacheDir, "transcoded_fb_${h}_${System.currentTimeMillis()}.mp4")
            if (runTranscode(context, input, outFile, h, 800_000)) {
                if (outFile.length() <= targetBytes) return Result(outFile, h)
            }
        }
        return null
    }

    @OptIn(UnstableApi::class)
    private fun runTranscode(
        context: Context,
        input: File,
        output: File,
        height: Int,
        targetBitrateBps: Int
    ): Boolean {
        val latch = CountDownLatch(1)
        var success = false
        val mainHandler = Handler(Looper.getMainLooper())
        var transformerRef: Transformer? = null

        mainHandler.post {
            try {
                val listener = object : Transformer.Listener {
                    override fun onCompleted(
                        composition: Composition,
                        exportResult: ExportResult
                    ) {
                        DiagnosticLogger.log("[TRANSCODE] Transformer.onCompleted -> ${output.absolutePath}")
                        success = true
                        latch.countDown()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        DiagnosticLogger.log("[TRANSCODE] Transformer.onError: ${exportException.errorCodeName}: ${exportException.message}")
                        latch.countDown()
                    }
                }

                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder()
                            .setBitrate(targetBitrateBps)
                            .build()
                    )
                    .build()

                val transformer = Transformer.Builder(context)
                    .setEncoderFactory(encoderFactory)
                    .setLooper(Looper.getMainLooper())
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(listener)
                    .build()

                transformerRef = transformer

                val effects = Effects(
                    /* audioProcessors = */ emptyList(),
                    /* videoEffects = */ listOf(Presentation.createForHeight(height))
                )

                val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
                    .setEffects(effects)
                    .build()

                DiagnosticLogger.log("[TRANSCODE] Transformer.start -> ${output.absolutePath}")
                transformer.start(edited, output.absolutePath)
            } catch (e: Exception) {
                DiagnosticLogger.log("[TRANSCODE] Exception: ${e.javaClass.simpleName}: ${e.message}")
                latch.countDown()
            }
        }

        val completed = try {
            latch.await(120, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            DiagnosticLogger.log("[TRANSCODE] Interrupted while waiting for transcode pass")
            false
        }

        if (!completed) {
            DiagnosticLogger.log("[TRANSCODE] Timeout or interruption waiting for transcode pass (${height}px)")
            mainHandler.post {
                try {
                    transformerRef?.cancel()
                } catch (e: Exception) {
                    DiagnosticLogger.log("[TRANSCODE] Exception cancelling transformer: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }

        DiagnosticLogger.log("[TRANSCODE] Latch released. success=$success, outputExists=${output.exists()}")
        return success && output.exists()
    }
}