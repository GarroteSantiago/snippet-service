package org.snippet.snippet.clients

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.snippet.snippet.clients.auth0.Auth0Config
import org.snippet.snippet.clients.auth0.Auth0ManagementClient
import org.snippet.snippet.clients.auth0.Auth0ManagementProperties
import org.snippet.snippet.common.error.UpstreamException
import org.snippet.snippet.common.http.RestClientConfig
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The client is wired from `auth0.management.*`, and missing values don't stop the app from starting. */
class Auth0ConfigTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(HttpMessageConvertersAutoConfiguration::class.java, RestClientAutoConfiguration::class.java),
            ).withUserConfiguration(RestClientConfig::class.java, Auth0Config::class.java, Auth0ManagementClient::class.java)

    @Test
    fun `starts without the m2m credentials and fails only when used`() {
        runner.run { context ->
            assertFalse(context.getBean(Auth0ManagementProperties::class.java).configured)
            assertThrows<UpstreamException> { context.getBean(Auth0ManagementClient::class.java).exists("auth0|bob") }
        }
    }

    @Test
    fun `binds the credentials`() {
        runner
            .withPropertyValues(
                "auth0.management.domain=tenant.test",
                "auth0.management.client-id=id",
                "auth0.management.client-secret=secret",
            ).run { context ->
                assertEquals(
                    Auth0ManagementProperties("tenant.test", "id", "secret"),
                    context.getBean(Auth0ManagementProperties::class.java),
                )
            }
    }
}
