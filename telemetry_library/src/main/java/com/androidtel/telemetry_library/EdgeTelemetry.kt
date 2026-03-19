package com.androidtel.telemetry_library

import com.androidtel.telemetry_library.core.TelemetryManager

/**
 * Backward compatibility wrapper for EdgeTelemetry (deprecated in v1.2.1)
 * All functionality has been merged into TelemetryManager
 * @deprecated Use TelemetryManager instead
 */
@Deprecated(
    message = "EdgeTelemetry has been merged into TelemetryManager. Use TelemetryManager.initialize() instead.",
    replaceWith = ReplaceWith("TelemetryManager"),
    level = DeprecationLevel.WARNING
)
typealias EdgeTelemetry = TelemetryManager
