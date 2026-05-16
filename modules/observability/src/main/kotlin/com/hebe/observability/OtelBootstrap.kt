@file:Suppress("TooGenericExceptionCaught", "MagicNumber")

package com.hebe.observability

import com.hebe.api.Observer
import com.hebe.api.ObserverEvent
import com.hebe.api.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import org.slf4j.LoggerFactory

object OtelBootstrap {
    private val log = LoggerFactory.getLogger(javaClass)

    fun createObserver(logbackObserver: LogbackObserver): Observer {
        val endpoint = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
        return if (endpoint.isNullOrBlank()) {
            log.debug("OTEL_EXPORTER_OTLP_ENDPOINT not set, using log-only observer")
            logbackObserver
        } else {
            log.info("OTel exporter active, endpoint={}", endpoint)
            val sdk =
                try {
                    AutoConfiguredOpenTelemetrySdk
                        .builder()
                        .addPropertiesSupplier { mapOf("otel.service.name" to "hebe") }
                        .build()
                        .openTelemetrySdk
                } catch (e: Exception) {
                    log.error("Failed to initialise OTel SDK, falling back to log-only: {}", e.message)
                    return logbackObserver
                }
            OtelObserver(logbackObserver, sdk.getTracer("com.hebe", "1.0.0"))
        }
    }
}

private class OtelObserver(
    private val delegate: LogbackObserver,
    private val tracer: Tracer,
) : Observer {
    override fun event(e: ObserverEvent) = delegate.event(e)

    override fun span(
        name: String,
        attrs: Map<String, Any>,
    ): Span {
        val otelSpan =
            tracer
                .spanBuilder(name)
                .apply {
                    attrs.forEach { (k, v) -> setAttribute(k, v.toString()) }
                }.startSpan()
        return object : Span {
            private val delegateSpan = delegate.span(name, attrs)

            override fun setAttribute(
                key: String,
                value: Any,
            ) {
                otelSpan.setAttribute(key, value.toString())
                delegateSpan.setAttribute(key, value)
            }

            override fun recordError(t: Throwable) {
                otelSpan.setStatus(StatusCode.ERROR, t.message ?: "error")
                otelSpan.recordException(t)
                delegateSpan.recordError(t)
            }

            override fun close() {
                otelSpan.end()
                delegateSpan.close()
            }
        }
    }
}
