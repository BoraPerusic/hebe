package com.talos.api

sealed class TalosException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class Config(
        message: String,
    ) : TalosException(message)

    class Provider(
        message: String,
        cause: Throwable? = null,
    ) : TalosException(message, cause)

    class Tool(
        message: String,
    ) : TalosException(message)

    class Plugin(
        message: String,
        cause: Throwable? = null,
    ) : TalosException(message, cause)

    class Security(
        message: String,
    ) : TalosException(message)

    class PolicyDenied(
        message: String,
    ) : TalosException(message)

    class Approval(
        message: String,
    ) : TalosException(message)

    class Memory(
        message: String,
    ) : TalosException(message)

    class Channel(
        message: String,
        cause: Throwable? = null,
    ) : TalosException(message, cause)
}
