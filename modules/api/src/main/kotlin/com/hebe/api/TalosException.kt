package com.hebe.api

sealed class HebeException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class Config(
        message: String,
    ) : HebeException(message)

    class Provider(
        message: String,
        cause: Throwable? = null,
    ) : HebeException(message, cause)

    class Tool(
        message: String,
    ) : HebeException(message)

    class Plugin(
        message: String,
        cause: Throwable? = null,
    ) : HebeException(message, cause)

    class Security(
        message: String,
    ) : HebeException(message)

    class PolicyDenied(
        message: String,
    ) : HebeException(message)

    class Approval(
        message: String,
    ) : HebeException(message)

    class Memory(
        message: String,
    ) : HebeException(message)

    class Channel(
        message: String,
        cause: Throwable? = null,
    ) : HebeException(message, cause)
}
