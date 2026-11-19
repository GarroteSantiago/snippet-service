package org.snippet.snippet.common.observability

import jakarta.servlet.http.HttpServletRequest
import org.apache.catalina.connector.Connector
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.web.util.matcher.RequestMatcher

/**
 * `/prometheus` is for a scraper inside the Docker network, which has no JWT. Tomcat also listens
 * on `observability.metrics-port` (9464), which NGINX doesn't proxy, and only there `/prometheus`
 * is public ([MetricsRequests], used by `SecurityConfig`). On the main port it's denied, so it's
 * never reachable as `/api/prometheus`. `/health` stays on the main port as before.
 */
@Configuration
class MetricsPortConfig(
    @param:Value("\${observability.metrics-port:0}") private val port: Int,
) {
    @Bean
    fun metricsConnector() =
        WebServerFactoryCustomizer<TomcatServletWebServerFactory> { factory ->
            if (port > 0) factory.addAdditionalTomcatConnectors(Connector().also { it.port = port })
        }
}

/** Requests for `/prometheus`: [onMetricsPort] came through the internal port, [elsewhere] through the main one. */
class MetricsRequests(
    private val port: Int,
) {
    val onMetricsPort = RequestMatcher { isMetrics(it) && port > 0 && it.localPort == port }

    val elsewhere = RequestMatcher { isMetrics(it) && !onMetricsPort.matches(it) }

    private fun isMetrics(request: HttpServletRequest) = request.requestURI.removeSuffix("/") == PATH

    companion object {
        const val PATH = "/prometheus"
    }
}
