package dev.nexa.youtube

import android.util.Log

internal object TokenLog {
    fun tag(tag: String): Logger = Logger(tag)

    class Logger(private val tag: String) {
        fun d(message: String) {
            try { Log.d(tag, message) } catch (_: Throwable) {}
        }
        fun w(message: String) {
            try { Log.w(tag, message) } catch (_: Throwable) {}
        }
        fun e(message: String) {
            try { Log.e(tag, message) } catch (_: Throwable) {}
        }
        fun e(message: String, throwable: Throwable) {
            try { Log.e(tag, message, throwable) } catch (_: Throwable) {}
        }
    }
}
