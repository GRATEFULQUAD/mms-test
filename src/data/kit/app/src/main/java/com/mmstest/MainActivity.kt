package com.mmstest

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mmstest.mms.DiagnosticLogger
import com.mmstest.mms.MmsSender
import com.mmstest.mms.VideoTranscoder
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var dest: EditText
    private lateinit var text: EditText
    private lateinit var maxLabel: TextView
    private lateinit var maxSeekBar: SeekBar
    private lateinit var logView: TextView

    private var pickedVideoFile: File? = null
    private var pickedVideoMime: String = "video/mp4"
    private var maxBytes: Int = 1_000_000

    private lateinit var mmsSender: MmsSender
    private val transcoder = VideoTranscoder()

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.data
            if (uri != null) onVideoPicked(uri) else DiagnosticLogger.log("[PICK] No URI returned")
        } else {
            DiagnosticLogger.log("[PICK] Pick Video cancelled")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dest = findViewById(R.id.dest)
        text = findViewById(R.id.text)
        maxLabel = findViewById(R.id.maxLabel)
        maxSeekBar = findViewById(R.id.maxSeekBar)
        logView = findViewById(R.id.log)
        logView.movementMethod = ScrollingMovementMethod()

        mmsSender = MmsSender(this)

        DiagnosticLogger.listener = { newText -> logView.text = newText }
        DiagnosticLogger.log("=== MMS Test started ===")
        DiagnosticLogger.log("Package: $packageName")
        DiagnosticLogger.log("Device: ${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}")
        DiagnosticLogger.log("Log: every stage of pick -> transcode -> PDU -> dispatch -> result is shown here.")

        findViewById<Button>(R.id.pickBtn).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
            }
            pickVideoLauncher.launch(intent)
        }

        // SeekBar: (100 + progress) KB, i.e. 100..2000 KB. Default 1000 KB.
        maxSeekBar.max = 1900
        maxSeekBar.progress = 900
        updateMaxLabel(maxSeekBar.progress)
        maxSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateMaxLabel(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<Button>(R.id.sendBtn).setOnClickListener { onSend() }

        findViewById<Button>(R.id.copyLogBtn).setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("mms_log", DiagnosticLogger.full()))
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), 1)
        } else {
            DiagnosticLogger.log("[PERM] SEND_SMS already granted")
        }
    }

    private fun updateMaxLabel(progress: Int) {
        maxBytes = (progress + 100) * 1024
        maxLabel.text = "Max attachment: ${maxBytes / 1024} KB"
    }

    private fun onVideoPicked(uri: Uri) {
        val mime = contentResolver.getType(uri) ?: "video/mp4"
        pickedVideoMime = mime
        val ext = when {
            mime.contains("3gpp") -> "3gp"
            mime.contains("webm") -> "webm"
            else -> "mp4"
        }
        val outFile = File(cacheDir, "picked_video.$ext")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { input.copyTo(it) }
            } ?: run {
                DiagnosticLogger.log("[PICK] ERROR: could not open input stream for $uri")
                return
            }
        } catch (e: Exception) {
            DiagnosticLogger.log("[PICK] ERROR copying video: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        pickedVideoFile = outFile

        DiagnosticLogger.log("[PICK] Selected video URI: $uri")
        DiagnosticLogger.log("[PICK] Decode mime-type: $mime")
        DiagnosticLogger.log("[PICK] Cached file: ${outFile.absolutePath}")
        DiagnosticLogger.log("[PICK] Original size: ${outFile.length()} bytes (${fmt(outFile.length())})")
        DiagnosticLogger.log("[PICK] Ceiling: $maxBytes bytes (${fmt(maxBytes.toLong())})")
    }

    private fun onSend() {
        val destStr = dest.text.toString().trim()
        val textStr = text.text.toString().trim()
        if (destStr.isEmpty()) {
            DiagnosticLogger.log("[SEND] ERROR: recipient is empty")
            return
        }
        val src = pickedVideoFile
        if (src == null || !src.exists()) {
            DiagnosticLogger.log("[SEND] ERROR: no video picked")
            return
        }
        findViewById<Button>(R.id.sendBtn).isEnabled = false
        DiagnosticLogger.log("[SEND] ---- send initiated ----")

        Thread {
            try {
                runSend(destStr, textStr, src)
            } catch (e: Throwable) {
                DiagnosticLogger.log("[SEND] FATAL: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runOnUiThread { findViewById<Button>(R.id.sendBtn).isEnabled = true }
            }
        }.start()
    }

    private fun runSend(destStr: String, textStr: String, src: File) {
        val originalBytes = src.readBytes()
        DiagnosticLogger.log("[SEND] Original video bytes: ${originalBytes.size} (${fmt(originalBytes.size.toLong())})")

        var attachmentBytes: ByteArray = originalBytes
        var attachmentMime: String = pickedVideoMime
        var attachmentName: String = src.name

        if (originalBytes.size > maxBytes) {
            DiagnosticLogger.log("[TRANSCODE] Original ${originalBytes.size} B exceeds ceiling ${maxBytes} B; transcoding ...")
            val result = transcoder.transcode(this@MainActivity, src, maxBytes)
            if (result != null) {
                attachmentBytes = result.file.readBytes()
                attachmentMime = "video/mp4"
                attachmentName = "video.mp4"
                DiagnosticLogger.log("[TRANSCODE] Using transcoded: ${attachmentBytes.size} bytes, MIME=$attachmentMime, height=${result.height}px")
                if (attachmentBytes.size > maxBytes) {
                    DiagnosticLogger.log("[TRANSCODE] ERROR: Transcoded file (${attachmentBytes.size} bytes) exceeds ceiling (${maxBytes} bytes); aborting send.")
                    return
                }
            } else {
                DiagnosticLogger.log("[TRANSCODE] Transcode FAILED; aborting send because attachment exceeds ceiling (${maxBytes} bytes).")
                return
            }
        } else {
            DiagnosticLogger.log("[SEND] Original within ceiling; skipping transcode.")
        }

        DiagnosticLogger.log("[SEND] Final attachment: ${attachmentBytes.size} bytes, MIME=$attachmentMime, name=$attachmentName")

        mmsSender.sendVideoMms(
            dest = destStr,
            text = textStr.ifEmpty { null },
            videoBytes = attachmentBytes,
            videoMime = attachmentMime,
            videoName = attachmentName,
            maxBytes = maxBytes
        )
    }

    private fun fmt(bytes: Long): String = String.format("%.2f KB", bytes / 1024.0)

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                DiagnosticLogger.log("[PERM] SEND_SMS granted")
            } else {
                DiagnosticLogger.log("[PERM] SEND_SMS DENIED -- sending will fail")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DiagnosticLogger.listener = null
    }
}