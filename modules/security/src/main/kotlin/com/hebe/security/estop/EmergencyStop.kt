package com.hebe.security.estop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

class EmergencyStop(
    private val scope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val stopFlag = AtomicBoolean(false)
    private val stopChannel = Channel<Unit>(Channel.UNLIMITED)

    val isStopRequested: Boolean
        get() = stopFlag.get()

    fun stopFlow(): Flow<Unit> = flow {
        stopChannel.receive()
    }

    suspend fun requestStop() {
        logger.warn("Emergency stop requested")
        stopFlag.set(true)
        stopChannel.send(Unit)
    }

    fun reset() {
        stopFlag.set(false)
    }
}
