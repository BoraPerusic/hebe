@file:Suppress("TooGenericExceptionCaught")

package com.hebe.cli.daemon

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

object Shutdown {
    private val log = LoggerFactory.getLogger(javaClass)

    fun installHook(
        pidFile: PidFile,
        onShutdown: suspend () -> Unit,
    ) {
        Runtime.getRuntime().addShutdownHook(
            Thread({
                log.info("shutdown signal received, draining")
                try {
                    runBlocking { onShutdown() }
                } catch (e: Exception) {
                    log.error("error during shutdown: {}", e.message, e)
                } finally {
                    pidFile.close()
                }
                log.info("shutdown complete")
            }, "hebe-shutdown"),
        )
    }
}
