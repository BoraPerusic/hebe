package com.hebe.observability

import ch.qos.logback.classic.Level
import java.time.Instant

class LogbackObserver

enum class LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    ;

    companion object {
        fun fromLevel(level: Level): LogLevel =
            when (level) {
                Level.TRACE -> TRACE
                Level.DEBUG -> DEBUG
                Level.INFO -> INFO
                Level.WARN -> WARN
                Level.ERROR -> ERROR
                else -> INFO
            }
    }
}

data class LogEvent(
    val timestamp: Instant,
    val level: LogLevel,
    val logger: String,
    val message: String,
    val throwable: Throwable? = null,
)
