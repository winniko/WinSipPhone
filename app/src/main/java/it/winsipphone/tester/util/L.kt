package it.winsipphone.tester.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Log con buffer circolare in memoria, consultabile e condivisibile dagli Strumenti. */
object L {
    private const val MAX = 800
    private val buffer = ArrayDeque<String>(MAX)
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.ITALY)

    fun i(tag: String, msg: String) = add("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = add("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = add("E", tag, msg, t)

    private fun add(level: String, tag: String, msg: String, t: Throwable?) {
        val full = "WinSipPhone/$tag"
        when (level) {
            "E" -> Log.e(full, msg, t)
            "W" -> Log.w(full, msg, t)
            else -> Log.i(full, msg)
        }
        val line = synchronized(fmt) { fmt.format(Date()) } + " $level/$tag: $msg" +
            (t?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: "")
        synchronized(buffer) {
            if (buffer.size >= MAX) buffer.removeFirst()
            buffer.addLast(line)
        }
    }

    fun dump(): String = synchronized(buffer) { buffer.joinToString("\n") }

    fun clear() = synchronized(buffer) { buffer.clear() }
}
