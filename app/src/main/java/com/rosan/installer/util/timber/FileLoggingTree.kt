package com.rosan.installer.util.timber

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.rosan.installer.util.timber.FileLoggingTree.Companion.MAX_LOG_FILES
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A Timber Tree that logs to a file in the background without blocking the UI thread.
 * Supports automatic log rotation based on file size and date.
 */
@Suppress("LogNotTimber")
class FileLoggingTree(context: Context) : Timber.DebugTree() {

    private val logDir: File = File(context.cacheDir, LOG_DIR_NAME)
    private val backgroundHandler: Handler
    private var currentLogFile: File? = null

    // Create a property for HandlerThread to access it later for quitting
    private val handlerThread: HandlerThread

    // Formatter for file names (e.g., "2025-01-29_14-00-00.log")
    private val fileNameDateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    // Formatter for checking if the day has changed (e.g., "2025-01-29")
    private val dayDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // Formatter for specific log entries (e.g., "2025-01-29 14:00:00.123")
    private val entryDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // Tracks the date string of the current log file to detect day changes
    private var currentFileDateStr: String = ""

    init {
        // Create the directory if it doesn't exist
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        // Initialize a background thread for file I/O operations to prevent UI blocking
        handlerThread = HandlerThread("AndroidFileLogger.Thread")
        handlerThread.start()
        backgroundHandler = Handler(handlerThread.looper)

        backgroundHandler.post {
            // Perform initial preparation and cleanup on the background thread
            ensureLogFileReady()
            cleanOldLogFiles()
        }
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Post the writing task to the background thread
        backgroundHandler.post {
            doLog(priority, tag, message, t)
        }
    }

    /**
     * Releases resources when the tree is uprooted.
     * Stops the background thread to prevent leaks.
     */
    fun release() {
        try {
            backgroundHandler.removeCallbacksAndMessages(null)
            handlerThread.quitSafely()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release FileLoggingTree resources", e)
        }
    }

    /**
     * Actual logging logic running on the background thread.
     */
    private fun doLog(priority: Int, tag: String?, message: String, t: Throwable?) {
        try {
            // Critical: Check if rotation is needed (new day or file too large) before every write
            ensureLogFileReady()

            val file = currentLogFile ?: return

            val timestamp = entryDateFormat.format(Date())
            val priorityStr = priorityToString(priority)

            // Build the log line: "Time Level/Tag: Message"
            val logLine = StringBuilder()
                .append(timestamp)
                .append(" ")
                .append(priorityStr)
                .append("/")
                .append(tag ?: "Unknown")
                .append(": ")
                .append(message)
                .append("\n")

            // Append stack trace if exception exists
            if (t != null) {
                logLine.append(Log.getStackTraceString(t)).append("\n")
            }

            // Append to the file
            file.appendText(logLine.toString())

        } catch (e: Exception) {
            // Fail silently to avoid crashing the app during logging.
            // MUST use android.util.Log here, NOT Timber, to avoid infinite recursion.
            Log.e(TAG, "Error writing to log file", e)
        }
    }

    /**
     * Ensures a valid log file exists for writing.
     * Creates a new file if:
     * 1. No directory exists.
     * 2. No file exists.
     * 3. The date has changed (new day).
     * 4. The current file exceeds the size limit.
     */
    private fun ensureLogFileReady() {
        val now = Date()
        val todayStr = dayDateFormat.format(now)
        val file = currentLogFile

        var needNewFile = false

        // Case 1: No directory exists
        if (!logDir.exists()) {
            logDir.mkdirs()
            needNewFile = true
        }

        // Case 2: No file exists yet
        if (file == null || !file.exists()) {
            needNewFile = true
        }
        // Case 3: Date changed (e.g., crossed midnight)
        else if (todayStr != currentFileDateStr) {
            needNewFile = true
        }
        // Case 4: File is too large (exceeds limit)
        else if (file.length() > MAX_FILE_SIZE) {
            needNewFile = true
        }

        if (needNewFile) {
            createNewLogFile(now, todayStr)
            // Cleanup old files whenever a new one is created to maintain the limit
            cleanOldLogFiles()
        }
    }

    private fun createNewLogFile(now: Date, todayStr: String) {
        try {
            val fileName = fileNameDateFormat.format(now) + ".log"
            val newFile = File(logDir, fileName)
            if (newFile.createNewFile()) {
                currentLogFile = newFile
                currentFileDateStr = todayStr
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create new log file", e)
        }
    }

    /**
     * Removes old log files, keeping only the latest [MAX_LOG_FILES].
     */
    private fun cleanOldLogFiles() {
        try {
            val files = logDir.listFiles { _, name -> name.endsWith(LOG_SUFFIX) } ?: return

            if (files.size > MAX_LOG_FILES) {
                // Sort by last modified (descending) and remove the oldest ones
                files.sortByDescending { it.lastModified() }

                for (i in MAX_LOG_FILES until files.size) {
                    files[i].delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean old logs", e)
        }
    }

    /**
     * Converts integer priority to string representation.
     */
    private fun priorityToString(priority: Int): String {
        return when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "A"
            else -> "?"
        }
    }

    companion object {
        private const val TAG = "FileLoggingTree"

        // Keep max 10 files (increased from 3 since files are now smaller due to rotation)
        private const val MAX_LOG_FILES = 10

        // Max file size: 2MB
        private const val MAX_FILE_SIZE = 2 * 1024 * 1024L

        const val LOG_DIR_NAME = "logs"
        const val LOG_SUFFIX = ".log"
    }
}