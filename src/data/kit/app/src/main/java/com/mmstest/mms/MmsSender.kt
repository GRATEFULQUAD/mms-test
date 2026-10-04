package com.mmstest.mms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import com.google.android.mms.pdu_alt.CharacterSets
import com.google.android.mms.pdu_alt.EncodedStringValue
import com.google.android.mms.pdu_alt.PduBody
import com.google.android.mms.pdu_alt.PduComposer
import com.google.android.mms.pdu_alt.PduPart
import com.google.android.mms.pdu_alt.SendReq
import java.io.File
import java.io.FileOutputStream

/**
 * Composes an M-Send.req PDU and dispatches it via SmsManager.sendMultimediaMessage.
 *
 * Why this path (researched):
 *  - sendMultimediaMessage (API 21+) is the supported Android MMS send API.
 *  - It reads a *raw PDU* from the supplied content Uri and hands it to the
 *    platform MMS service, which talks to the carrier MMSC.
 *  - The PDU bytes are produced by the AOSP-proven PduComposer, not hand-rolled.
 *  - The Uri is a FileProvider Uri on a cache file, which works reliably across
 *    OEM MMS providers (unlike openInputStream on content://mms/<id>).
 */
class MmsSender(private val context: Context) {

    companion object {
        const val ACTION_MMS_SENT = "com.mmstest.MMS_SENT"
    }

    private var sentReceiver: BroadcastReceiver? = null

    /**
     * @param dest       recipient phone number
     * @param text       optional text body (null/empty = no text part)
     * @param videoBytes the (already size-bounded) video bytes to attach
     * @param videoMime  mime type, e.g. "video/mp4"
     * @param videoName  content-location name, e.g. "video.mp4"
     * @param maxBytes   the ceiling that was used when bounding the video
     */
    fun sendVideoMms(
        dest: String,
        text: String?,
        videoBytes: ByteArray,
        videoMime: String,
        videoName: String,
        maxBytes: Int
    ) {
        DiagnosticLogger.log("[MMS] ---- building MMS PDU ----")
        DiagnosticLogger.log("[MMS] To: $dest")
        DiagnosticLogger.log("[MMS] Text: ${text?.length ?: 0} chars")
        DiagnosticLogger.log("[MMS] Attachment: ${videoBytes.size} bytes, MIME=$videoMime, name=$videoName")
        DiagnosticLogger.log("[MMS] Ceiling used: $maxBytes bytes")

        try {
            // 1) Build SendReq. The default ctor sets Message-Type, MMS-Version,
            //    From=insert-address-token, Transaction-Id, and Content-Type.
            val sendReq = SendReq()
            sendReq.addTo(EncodedStringValue(dest))

            val body = PduBody()

            // optional text part
            if (!text.isNullOrEmpty()) {
                val tp = PduPart()
                tp.setData(text.toByteArray(Charsets.UTF_8))
                tp.setContentType("text/plain".toByteArray())
                tp.setCharset(CharacterSets.UTF_8)
                tp.setContentId("text_0".toByteArray())
                tp.setContentLocation("_text_0.txt".toByteArray())
                body.addPart(tp)
                DiagnosticLogger.log("[MMS] Added text part (${text.length} chars)")
            }

            // video part
            val vp = PduPart()
            vp.setData(videoBytes)
            vp.setContentType(videoMime.toByteArray())
            vp.setContentId("video_0".toByteArray())
            vp.setContentLocation(videoName.toByteArray())
            body.addPart(vp)
            DiagnosticLogger.log("[MMS] Added video part (${videoBytes.size} bytes, $videoMime)")

            sendReq.setBody(body)
            // multipart.mixed gives the broadest carrier acceptance (no SMIL start param).
            sendReq.setContentType("application/vnd.wap.multipart.mixed".toByteArray())

            // 2) Compose the raw PDU
            DiagnosticLogger.log("[MMS] Invoking PduComposer.make() ...")
            val pduBytes: ByteArray = PduComposer(context, sendReq).make()
                ?: run {
                    DiagnosticLogger.log("[MMS] ERROR: PDU composition returned null")
                    return
                }
            DiagnosticLogger.log("[MMS] PDU composed: ${pduBytes.size} bytes (headers + multipart body)")

            // 3) Write PDU to a cache file and expose via FileProvider
            val pduFile = File(context.cacheDir, "send_${System.currentTimeMillis()}.mms")
            FileOutputStream(pduFile).use { it.write(pduBytes) }
            val authority = "${context.packageName}.mmsprovider"
            val contentUri: Uri = FileProvider.getUriForFile(context, authority, pduFile)
            DiagnosticLogger.log("[MMS] PDU file: ${pduFile.absolutePath} (${pduFile.length()} bytes)")
            DiagnosticLogger.log("[MMS] FileProvider authority: $authority")
            DiagnosticLogger.log("[MMS] contentUri: $contentUri")

            // 4) PendingIntent fired by the platform when the send completes
            val sentIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_MMS_SENT).apply { setPackage(context.packageName) },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            registerSentReceiver()

            // 5) Dispatch via the supported sendMultimediaMessage API
            val smsManager: SmsManager =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
            DiagnosticLogger.log("[MMS] SmsManager: $smsManager")
            DiagnosticLogger.log("[MMS] Dispatching sendMultimediaMessage(context, uri, null, null, sentIntent) ...")
            try {
                smsManager.sendMultimediaMessage(context, contentUri, null, null, sentIntent)
                DiagnosticLogger.log("[MMS] Dispatch returned without throwing. Awaiting result broadcast ...")
            } catch (se: SecurityException) {
                DiagnosticLogger.log("[MMS] SecurityException on dispatch: ${se.message}")
                DiagnosticLogger.log("[MMS] => Grant SEND_SMS in system Settings -> Apps -> MMS Test.")
                unregisterSentReceiver()
            } catch (e: Exception) {
                DiagnosticLogger.log("[MMS] Exception on dispatch: ${e.javaClass.simpleName}: ${e.message}")
                unregisterSentReceiver()
            }
        } catch (t: Throwable) {
            DiagnosticLogger.log("[MMS] FATAL during send: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun registerSentReceiver() {
        unregisterSentReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val code = resultCode
                DiagnosticLogger.log("[MMS] ---- send result broadcast ----")
                DiagnosticLogger.log("[MMS] Result code: $code (${describeResult(code)})")
                if (code == Activity.RESULT_OK) {
                    DiagnosticLogger.log("[MMS] >>> SUCCESS: MMS handed to carrier for delivery.")
                } else {
                    DiagnosticLogger.log("[MMS] >>> FAILURE: see the MMS_ERROR_* code above.")
                }
                unregisterSentReceiver()
            }
        }
        val filter = IntentFilter(ACTION_MMS_SENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        sentReceiver = receiver
        DiagnosticLogger.log("[MMS] Registered result BroadcastReceiver for $ACTION_MMS_SENT")
    }

    private fun unregisterSentReceiver() {
        sentReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        sentReceiver = null
    }

    private fun describeResult(code: Int): String = when (code) {
        Activity.RESULT_OK -> "RESULT_OK (success)"
        SmsManager.MMS_ERROR_NONE -> "MMS_ERROR_NONE"
        SmsManager.MMS_ERROR_RETRY -> "MMS_ERROR_RETRY"
        SmsManager.MMS_ERROR_INVALID_TRANSACTION -> "MMS_ERROR_INVALID_TRANSACTION"
        SmsManager.MMS_ERROR_NO_APN -> "MMS_ERROR_NO_APN"
        SmsManager.MMS_ERROR_UNABLE_TO_CONNECT_MMS -> "MMS_ERROR_UNABLE_TO_CONNECT_MMS"
        SmsManager.MMS_ERROR_IO_ERROR -> "MMS_ERROR_IO_ERROR"
        SmsManager.MMS_ERROR_HTTP_FAILURE -> "MMS_ERROR_HTTP_FAILURE"
        SmsManager.MMS_ERROR_DATA_DISABLED -> "MMS_ERROR_DATA_DISABLED"
        SmsManager.MMS_ERROR_INVALID_SUB_ID -> "MMS_ERROR_INVALID_SUB_ID"
        else -> "UNKNOWN($code)"
    }
}