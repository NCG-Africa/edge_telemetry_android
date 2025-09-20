package com.androidtel.telemetry_library.core.crash

/**
 * Crash fingerprinting algorithm that matches the Flutter SDK exactly
 */
object CrashFingerprinter {
    
    /**
     * Generate crash fingerprint with exact algorithm:
     * Format: <ErrorType>_<MessageHashCode>_<TopStackFrameHashCode>
     */
    fun generateCrashFingerprint(error: Throwable, stackTrace: String): String {
        val errorType = error.javaClass.simpleName
        val errorMessage = error.message ?: "null"
        val topStackFrame = extractTopStackFrame(stackTrace)
        
        return "${errorType}_${errorMessage.hashCode()}_${topStackFrame.hashCode()}"
    }
    
    /**
     * Generate crash fingerprint from message and stack trace
     */
    fun generateCrashFingerprint(errorMessage: String, stackTrace: String): String {
        val errorType = extractErrorTypeFromStackTrace(stackTrace)
        val topStackFrame = extractTopStackFrame(stackTrace)
        
        return "${errorType}_${errorMessage.hashCode()}_${topStackFrame.hashCode()}"
    }
    
    /**
     * Extract the top relevant stack frame (first non-system frame)
     */
    private fun extractTopStackFrame(stackTrace: String): String {
        val lines = stackTrace.lines()
        
        // Find first stack frame that contains "at " and is not from java.lang
        val topFrame = lines.firstOrNull { line ->
            line.trim().startsWith("at ") && 
            !line.contains("java.lang") &&
            !line.contains("android.os") &&
            !line.contains("dalvik.system")
        }
        
        return topFrame?.trim() ?: "unknown"
    }
    
    /**
     * Extract error type from stack trace when only message is available
     */
    private fun extractErrorTypeFromStackTrace(stackTrace: String): String {
        val lines = stackTrace.lines()
        
        // Look for the first line that contains an exception class name
        val exceptionLine = lines.firstOrNull { line ->
            line.contains("Exception") || 
            line.contains("Error") || 
            line.contains("Throwable")
        }
        
        return if (exceptionLine != null) {
            // Extract class name from the line
            val parts = exceptionLine.split(":")
            if (parts.isNotEmpty()) {
                val className = parts[0].trim()
                className.substringAfterLast(".")
            } else {
                "UnknownException"
            }
        } else {
            "UnknownException"
        }
    }
}
