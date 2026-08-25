package pl.trikimusic.controller.domain.model

enum class LogCategory {
    BLE,
    PROTOCOL,
    IMU,
    CONTROL,
    MEDIA,
    SERVICE,
    PERMISSION,
}

data class AppLogEntry(
    val timestampMillis: Long,
    val category: LogCategory,
    val message: String,
)
