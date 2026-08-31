package com.example.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Production-grade persistent diagnostic logger with automatic 7-day retention purge.
 * Writes diagnostic logs to disk and provides export functionality.
 */
class DiagnosticsLogger(private val context: Context) {

  companion object {
    private const val TAG = "DiagnosticsLogger"
    private const val LOG_FILE_NAME = "spatial_diagnostics.log"
    private const val RETENTION_DAYS_MS = 7L * 24 * 60 * 60 * 1000L
  }

  private val logFile: File by lazy {
    File(context.filesDir, LOG_FILE_NAME)
  }

  private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

  init {
    purgeOldLogs()
  }

  @Synchronized
  fun log(tag: String, message: String, level: String = "INFO") {
    val timestamp = System.currentTimeMillis()
    val formattedTime = dateFormat.format(Date(timestamp))
    val logLine = "[$formattedTime] [$level] [$tag]: $message\n"

    try {
      FileWriter(logFile, true).use { writer ->
        writer.append(logLine)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to write diagnostic log to disk: ${e.message}")
    }
  }

  @Synchronized
  fun readRecentLogs(maxLines: Int = 100): List<String> {
    return try {
      if (!logFile.exists()) return emptyList()
      logFile.readLines().takeLast(maxLines)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to read diagnostic logs: ${e.message}")
      emptyList()
    }
  }

  @Synchronized
  fun getExportLogContent(): String {
    return try {
      if (!logFile.exists()) return "No diagnostic logs recorded yet."
      logFile.readText()
    } catch (e: Exception) {
      "Error reading logs: ${e.message}"
    }
  }

  @Synchronized
  fun clearLogs() {
    try {
      if (logFile.exists()) {
        logFile.delete()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to clear diagnostic logs: ${e.message}")
    }
  }

  /**
   * Purges diagnostic log lines or backup files older than 7 days.
   */
  private fun purgeOldLogs() {
    try {
      if (logFile.exists()) {
        val fileAgeMs = System.currentTimeMillis() - logFile.lastModified()
        if (fileAgeMs > RETENTION_DAYS_MS) {
          logFile.delete()
          Log.i(TAG, "Purged diagnostic log file older than 7 days.")
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error checking log retention: ${e.message}")
    }
  }
}
