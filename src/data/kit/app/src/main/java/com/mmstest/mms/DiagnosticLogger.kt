package com.mmstest.mms

import android.os.Handler
import android.os.Looper

/**
 * One shared, thread-safe diagnostic log for the whole app.
 * Any thread logs; the UI is updated on the main thread via [listener].
 */
object DiagnosticLogger {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sb = StringBuilder()

    /** Set by MainActivity to a function that renders the full log text on screen. */
    var listener: ((String) -> Unit)? = null

    fun log(line: String) {
        synchronized(this) {
            sb.append('[').append(System.currentTimeMillis()).append("] ")
                .append(line).append('\n')
        }
        val snapshot = synchronized(this) { sb.toString() }
        mainHandler.post { listener?.invoke(snapshot) }
    }

    fun full(): String = synchronized(this) { sb.toString() }

    fun clear() {
        synchronized(this) { sb.setLength(0) }
        mainHandler.post { listener?.invoke("") }
    }
}