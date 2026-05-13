package com.hebe.cli

import com.hebe.channels.ChannelManagerImpl
import com.hebe.channels.telegram.TelegramChannel
import com.hebe.channels.telegram.TelegramWebhookRoute
import com.hebe.channels.web.Routes
import com.hebe.channels.web.WebChannel
import com.hebe.gateway.Gateway
import com.hebe.gateway.MemoryRoutes
import com.hebe.gateway.ReceiptsRoutes
import java.nio.file.Path

/**
 * Wires channel modules into the Gateway.
 *
 * Call [applyToGateway] inside the `configureRoutes` lambda passed to [Gateway.start].
 * Call [registerChannels] on the [ChannelManagerImpl] before starting it.
 */
class ChannelWiring(
    private val webChannel: WebChannel,
    private val telegramWebhookRoute: TelegramWebhookRoute? = null,
    private val memoryStore: com.hebe.api.MemoryStore? = null,
    private val receiptsDir: Path? = null,
) {
    fun applyToGateway(routing: io.ktor.server.routing.Routing) {
        Routes.register(routing, webChannel)
        telegramWebhookRoute?.register(routing)
        memoryStore?.let { MemoryRoutes.register(routing, it) }
        receiptsDir?.let { ReceiptsRoutes.register(routing, it) }
    }

    suspend fun registerChannels(
        manager: ChannelManagerImpl,
        telegramChannel: TelegramChannel? = null,
    ) {
        manager.register(webChannel)
        telegramChannel?.let { manager.register(it) }
    }
}
