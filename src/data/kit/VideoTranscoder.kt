package com.mmstest.mms

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shrinks a video under the MMS size ceiling using Media3 Transformer.
 *
 * Strategy: keep H.264/AAC, halve the frame height each pass (720 -> 480 ->
 * 360 -> 240 -> 144). Halving the height roughly quarters the bitrate, so a
 * couple of passes get most short clips under ~1 MB. Transformer handles all
 * the MediaCodec/Surface/EGL complexity internally and reliably.
 *
 * On any failure we return null; the caller then falls back to the original
 * (which the carrier will likely reject, producing a clear MMS_ERROR in the log
 * -- still useful diagnostic evidence).
 */
class VideoTranscoder {

    data class Result(val file: File, val height: Int)

    private val heights = listOf(720, 480, 360, 240, 144)

    @OptIn(UnstableApi::class)
    fun transcode(context: Context, input: File, targetBytes: Int): Result? {
        var best: File? = null
        var bestH = 0

        for ((i, h) in heights.withIndex()) {
            DiagnosticLogger.log("[TRANSCODE] Pass ${i + 1}: target height=$h px")
            val outFile = File(context.cacheDir, "transcoded_${h}_${System.currentTimeMillis()}.mp4")

            if (!runTranscode(context, input, outFile, h)) {
                DiagnosticLogger.log("[TRANSCODE] Pass ${i + 1} failed; stopping transcode attempts.")
                break
            }

            val size = outFile.length()
            DiagnosticLogger.log("[TRANSCODE] Pass ${i + 1} produced $size bytes")
            best = outFile
            bestH = h

            if (size <= targetBytes) {
                DiagnosticLogger.log("[TRANSCODE] Under ceiling ($targetBytes B). Done at pass ${i + 1}.")
                return Result(outFile, h)
            }
            DiagnosticLogger.log("[TRANSCODE] Still over ceiling; downscaling further ...")
        }

        return best?.let {
            DiagnosticLogger.log("[TRANSCODE] Could not get under ceiling; using smallest pass (${bestH}px, ${it.length()} B).")
            Result(it, bestH)
        }
    }

    @OptIn(UnstableApi::class)
    private fun runTranscode(context: Context, input: File, output: File, height: Int): Boolean {
        val latch = CountDownLatch(1)
        var success = false

        val listener = object : Transformer.Listener {
            override fun onCompleted(
                composition: Composition,
                exportResult: ExportResult
            ) {
                DiagnosticLogger.log("[TRANSCODE] Transformer.onCompleted -> ${exportResult.exportedUri ?: output.absolutePath}")
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

        return try {
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(listener)
                .build()

            val effects = Effects(
                /* audioProcessors = */ emptyList(),
                /* videoEffects = */ listOf(Presentation.createForHeight(height))
            )

            val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
                .setEffects(effects)
                .build()

            DiagnosticLogger.log("[TRANSCODE] Transformer.start -> ${output.absolutePath}")
            transformer.start(edited, output.absolutePath)

            latch.await(120, TimeUnit.SECONDS)
            DiagnosticLogger.log("[TRANSCODE] Latch released. success=$success, outputExists=${output.exists()}")
            success && output.exists()
        } catch (e: Exception) {
            DiagnosticLogger.log("[TRANSCODE] Exception: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
}