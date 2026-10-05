package com.mmstest.mms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.FileProvider
import com.google.android.mms.pdu_alt.CharacterSets
import com.google.android.mms.pdu_alt.EncodedStringValue
import com.google.android.mms.pdu_alt.PduBody
import com.google.android.mms.pdu_alt.PduComposer
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.PduPart
import com.google.android.mms.pdu_alt.SendReq
import java.io.File
import java.io.FileOutputStream

/**
 * Composes an M-Send.req PDU and dispatches it via SmsManager.sendMultimediaMessage.
 *
 * Provides granular diagnostic logging across distinct failure domains:
 * 1) [SIM_DIAG] Subscription ID and SIM selection
 * 2) [CARRIER_DIAG] Carrier configuration and MMSC overrides
 * 3) [URI_DIAG] FileProvider / open / read failure and system URI grants
 * 4) [PDU_DIAG] PDU construction / format failure
 * 5) [SERVICE_DIAG] Android MMS service local pipeline failure
 */
class MmsSender(private val context: Context) {

    companion object {
        const val ACTION_MMS_SENT = "com.mmstest.MMS_SENT"
    }

    private var sentReceiver: BroadcastReceiver? = null
    private var dispatchStartTime: Long = 0L

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
            // 1) Build SendReq with complete WAP MMS 1.2 headers
            val sendReq = SendReq()
            sendReq.addTo(EncodedStringValue(dest))
            sendReq.date = System.currentTimeMillis() / 1000L
            sendReq.messageClass = "personal".toByteArray()
            sendReq.expiry = 604800L // 7 days in seconds

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

            // 2) Compose raw PDU & validate [PDU_DIAG]
            DiagnosticLogger.log("[MMS] Invoking PduComposer.make() ...")
            val pduBytes: ByteArray = PduComposer(context, sendReq).make()
                ?: run {
                    DiagnosticLogger.log("[PDU_DIAG] ERROR: PduComposer.make() returned null - PDU composition failed!")
                    return
                }
            DiagnosticLogger.log("[MMS] PDU composed: ${pduBytes.size} bytes (headers + multipart body)")

            // Validate composed PDU by re-parsing with PduParser
            try {
                val parsedGenericPdu = PduParser(pduBytes).parse()
                if (parsedGenericPdu != null) {
                    val mType = parsedGenericPdu.messageType
                    DiagnosticLogger.log("[PDU_DIAG] PduParser re-parse: SUCCESS (messageType=0x${Integer.toHexString(mType)})")
                    if (parsedGenericPdu is SendReq) {
                        val parsedBody = parsedGenericPdu.body
                        val partsNum = parsedBody?.partsNum ?: 0
                        DiagnosticLogger.log("[PDU_DIAG] SendReq headers valid: date=${parsedGenericPdu.date}, partsCount=$partsNum")
                        if (parsedBody != null) {
                            for (i in 0 until partsNum) {
                                val part = parsedBody.getPart(i)
                                val mimeStr = part.contentType?.let { String(it) } ?: "unknown"
                                val locStr = part.contentLocation?.let { String(it) } ?: "none"
                                val partSize = part.data?.size ?: 0
                                DiagnosticLogger.log("[PDU_DIAG] Part #$i: MIME=$mimeStr, loc=$locStr, bytes=$partSize")
                            }
                        }
                    } else {
                        DiagnosticLogger.log("[PDU_DIAG] WARNING: Re-parsed PDU is not SendReq (type=0x${Integer.toHexString(mType)})")
                    }
                } else {
                    DiagnosticLogger.log("[PDU_DIAG] ERROR: PduParser.parse() returned null - PDU construction/format failure!")
                }
            } catch (pe: Exception) {
                DiagnosticLogger.log("[PDU_DIAG] ERROR during PduParser re-parse: ${pe.javaClass.simpleName}: ${pe.message}")
            }

            // 3) Write PDU to cache file and verify FileProvider URI accessibility [URI_DIAG]
            val pduFile = File(context.cacheDir, "send_${System.currentTimeMillis()}.mms")
            FileOutputStream(pduFile).use { it.write(pduBytes) }
            pduFile.setReadable(true, false)

            val authority = "${context.packageName}.mmsprovider"
            val contentUri: Uri = FileProvider.getUriForFile(context, authority, pduFile)
            DiagnosticLogger.log("[MMS] PDU file: ${pduFile.absolutePath} (${pduFile.length()} bytes)")
            DiagnosticLogger.log("[MMS] FileProvider authority: $authority")
            DiagnosticLogger.log("[MMS] contentUri: $contentUri")

            // Test local ContentResolver read
            try {
                context.contentResolver.openInputStream(contentUri)?.use { stream ->
                    val bytesRead = stream.readBytes()
                    DiagnosticLogger.log("[URI_DIAG] Local ContentResolver.openInputStream SUCCESS: read ${bytesRead.size} bytes from $contentUri")
                } ?: run {
                    DiagnosticLogger.log("[URI_DIAG] ERROR: Local ContentResolver.openInputStream returned null for $contentUri")
                }
            } catch (ue: Exception) {
                DiagnosticLogger.log("[URI_DIAG] ERROR: Local ContentResolver.openInputStream threw ${ue.javaClass.simpleName}: ${ue.message}")
            }

            // Explicitly grant READ_URI_PERMISSION to candidate system telephony and MMS packages
            val mmsPackages = listOf(
                "android",
                "com.android.providers.telephony",
                "com.android.mms.service",
                "com.android.phone",
                "com.android.mms",
                "com.samsung.android.messaging",
                "com.google.android.apps.messaging"
            )
            for (pkg in mmsPackages) {
                try {
                    context.grantUriPermission(pkg, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    DiagnosticLogger.log("[URI_DIAG] Granted FLAG_GRANT_READ_URI_PERMISSION to $pkg")
                } catch (gpe: Exception) {
                    DiagnosticLogger.log("[URI_DIAG] grantUriPermission to $pkg: ${gpe.message}")
                }
            }

            // 4) Resolve active SMS Subscription ID explicitly [SIM_DIAG]
            val defaultSubId = SmsManager.getDefaultSmsSubscriptionId()
            DiagnosticLogger.log("[SIM_DIAG] SmsManager.getDefaultSmsSubscriptionId(): $defaultSubId")

            val smsManager: SmsManager = if (defaultSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java).createForSubscriptionId(defaultSubId)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getSmsManagerForSubscriptionId(defaultSubId)
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
            }
            DiagnosticLogger.log("[SIM_DIAG] Resolved SmsManager instance: $smsManager (subId: ${smsManager.subscriptionId})")

            // Inspect Carrier Configuration Values for this SmsManager instance [CARRIER_DIAG]
            val carrierConfig: Bundle? = try {
                smsManager.carrierConfigValues
            } catch (ce: Exception) {
                DiagnosticLogger.log("[CARRIER_DIAG] Exception reading carrierConfigValues: ${ce.message}")
                null
            }

            val carrierMax = carrierConfig?.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, -1) ?: -1
            if (carrierConfig != null && !carrierConfig.isEmpty) {
                val ua = carrierConfig.getString(SmsManager.MMS_CONFIG_USER_AGENT, "none")
                val uaProf = carrierConfig.getString(SmsManager.MMS_CONFIG_UA_PROF_URL, "none")
                DiagnosticLogger.log("[CARRIER_DIAG] Carrier Config loaded for subId ${smsManager.subscriptionId}: maxMsgSize=$carrierMax bytes, UA=$ua, UAProf=$uaProf")
            } else {
                DiagnosticLogger.log("[CARRIER_DIAG] WARNING: smsManager.carrierConfigValues is null or empty!")
            }

            val configOverrides = Bundle().apply {
                putInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, maxBytes)
                if (carrierConfig != null) {
                    val ua = carrierConfig.getString(SmsManager.MMS_CONFIG_USER_AGENT)
                    if (!ua.isNullOrEmpty()) putString(SmsManager.MMS_CONFIG_USER_AGENT, ua)
                    val uaProf = carrierConfig.getString(SmsManager.MMS_CONFIG_UA_PROF_URL)
                    if (!uaProf.isNullOrEmpty()) putString(SmsManager.MMS_CONFIG_UA_PROF_URL, uaProf)
                }
            }

            if (carrierMax > 0 && pduBytes.size > carrierMax) {
                DiagnosticLogger.log("[CARRIER_DIAG] NOTICE: PDU size (${pduBytes.size} B) exceeds carrier default maxMsgSize ($carrierMax B). Overriding maxMessageSize=$maxBytes B in configOverrides.")
            } else {
                DiagnosticLogger.log("[CARRIER_DIAG] PDU size (${pduBytes.size} B) is within carrier maxMsgSize ($carrierMax B). Setting configOverrides maxMessageSize=$maxBytes B.")
            }

            // 5) PendingIntent fired by the platform when send completes
            val sentIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_MMS_SENT).apply { setPackage(context.packageName) },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            registerSentReceiver()

            // 6) Dispatch via SmsManager [SERVICE_DIAG]
            DiagnosticLogger.log("[MMS] Dispatching sendMultimediaMessage(context, uri, null, configOverrides, sentIntent) ...")
            dispatchStartTime = System.currentTimeMillis()
            try {
                smsManager.sendMultimediaMessage(context, contentUri, null, configOverrides, sentIntent)
                DiagnosticLogger.log("[MMS] Dispatch returned without throwing. Awaiting result broadcast ...")
            } catch (se: SecurityException) {
                DiagnosticLogger.log("[SERVICE_DIAG] SecurityException on dispatch: ${se.message}")
                DiagnosticLogger.log("[SERVICE_DIAG] => Grant SEND_SMS in system Settings -> Apps -> MMS Test.")
                unregisterSentReceiver()
            } catch (e: Exception) {
                DiagnosticLogger.log("[SERVICE_DIAG] Exception on dispatch: ${e.javaClass.simpleName}: ${e.message}")
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
                val elapsedMs = if (dispatchStartTime > 0) System.currentTimeMillis() - dispatchStartTime else -1
                DiagnosticLogger.log("[MMS] ---- send result broadcast ----")
                DiagnosticLogger.log("[MMS] Result code: $code (${describeResult(code)}) [elapsed: ${elapsedMs}ms]")

                if (code == Activity.RESULT_OK) {
                    DiagnosticLogger.log("[SERVICE_DIAG] >>> SUCCESS: MMS handed to carrier for delivery (elapsed: ${elapsedMs}ms).")
                } else {
                    if (elapsedMs in 0..500) {
                        DiagnosticLogger.log("[SERVICE_DIAG] Failure occurred in local Android MMS Service / OS pipeline (${elapsedMs}ms < 500ms).")
                        DiagnosticLogger.log("[SERVICE_DIAG] Cause: System MMS service failed locally (e.g. PDU size exceeding mmsConfig or local I/O).")
                    } else {
                        DiagnosticLogger.log("[CARRIER_DIAG] Cellular MMSC network transaction failed after ${elapsedMs}ms.")
                        DiagnosticLogger.log("[CARRIER_DIAG] Reason: ${describeResultCodeReason(code)}")
                    }
                    DiagnosticLogger.log("[MMS] >>> FAILURE: see diagnostic error lines above.")
                }
                unregisterSentReceiver()
            }
        }
        val filter = IntentFilter(ACTION_MMS_SENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
        SmsManager.MMS_ERROR_UNSPECIFIED -> "MMS_ERROR_UNSPECIFIED"
        SmsManager.MMS_ERROR_INVALID_APN -> "MMS_ERROR_INVALID_APN"
        SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS -> "MMS_ERROR_UNABLE_CONNECT_MMS"
        SmsManager.MMS_ERROR_HTTP_FAILURE -> "MMS_ERROR_HTTP_FAILURE"
        SmsManager.MMS_ERROR_IO_ERROR -> "MMS_ERROR_IO_ERROR"
        SmsManager.MMS_ERROR_RETRY -> "MMS_ERROR_RETRY"
        SmsManager.MMS_ERROR_CONFIGURATION_ERROR -> "MMS_ERROR_CONFIGURATION_ERROR"
        SmsManager.MMS_ERROR_NO_DATA_NETWORK -> "MMS_ERROR_NO_DATA_NETWORK"
        else -> "UNKNOWN($code)"
    }

    private fun describeResultCodeReason(code: Int): String = when (code) {
        SmsManager.MMS_ERROR_IO_ERROR -> "MMS_ERROR_IO_ERROR (5): Carrier MMSC rejected connection/payload size, or local I/O failed."
        SmsManager.MMS_ERROR_INVALID_APN -> "MMS_ERROR_INVALID_APN: No MMSC APN configured for this carrier on the device."
        SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS -> "MMS_ERROR_UNABLE_CONNECT_MMS: Device could not establish mobile data socket connection to carrier MMSC."
        SmsManager.MMS_ERROR_HTTP_FAILURE -> "MMS_ERROR_HTTP_FAILURE: Carrier MMSC HTTP server responded with 4xx or 5xx error."
        SmsManager.MMS_ERROR_UNSPECIFIED -> "MMS_ERROR_UNSPECIFIED (1): Platform or MMSC reported unspecified error."
        SmsManager.MMS_ERROR_RETRY -> "MMS_ERROR_RETRY: Transient error reported by platform or carrier; retry suggested."
        SmsManager.MMS_ERROR_CONFIGURATION_ERROR -> "MMS_ERROR_CONFIGURATION_ERROR: Device or SIM configuration error."
        SmsManager.MMS_ERROR_NO_DATA_NETWORK -> "MMS_ERROR_NO_DATA_NETWORK: Mobile data is disabled or unavailable."
        else -> "Error code $code"
    }
}