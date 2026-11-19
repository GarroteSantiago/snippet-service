package org.snippet.snippet.clients.auth0

import com.fasterxml.jackson.annotation.JsonProperty
import org.snippet.snippet.clients.upstream
import org.snippet.snippet.common.error.UpstreamException
import org.snippet.snippet.common.http.ClientCredentialsToken
import org.snippet.snippet.common.http.OutboundHeadersInterceptor
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.time.Clock

/** `User` in the public contract. */
data class UserSummary(
    val id: String,
    val name: String,
)

/** A page of the user search; [count] is the total that match. */
data class UserSearchResult(
    val count: Long,
    val users: List<UserSummary>,
)

/** The users of the Auth0 tenant, for sharing (US-07). */
interface UserDirectory {
    /**
     * Users whose name, nickname or email starts with [name] (every user if it's blank),
     * without [excludedUserId]. [page] starts at 0.
     */
    fun search(
        name: String?,
        excludedUserId: String,
        page: Int,
        pageSize: Int,
    ): UserSearchResult

    fun exists(userId: String): Boolean
}

/**
 * Machine-to-machine credentials of the Snippet Service for the Auth0 Management API
 * (needs the `read:users` permission). Empty by default so the app starts without
 * them; only the calls that need them fail.
 */
@ConfigurationProperties("auth0.management")
data class Auth0ManagementProperties(
    /** Tenant domain, as in `tenant.us.auth0.com`. */
    val domain: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
) {
    val configured: Boolean get() = domain.isNotBlank() && clientId.isNotBlank() && clientSecret.isNotBlank()

    /** `https://{domain}`, also accepting a domain written with the scheme or a trailing slash. */
    val baseUrl: String get() =
        "https://" +
            domain
                .trim()
                .removePrefix("https://")
                .removePrefix("http://")
                .trimEnd('/')
}

@Configuration
@EnableConfigurationProperties(Auth0ManagementProperties::class)
class Auth0Config

/**
 * Auth0 Management API with a Client Credentials token, kept until shortly before it
 * expires (one token request per expiry, one request per search).
 *
 * The user's token must not reach Auth0: this client takes the [OutboundHeadersInterceptor]
 * out of its builder and always sends its own token.
 */
@Component
class Auth0ManagementClient(
    builder: RestClient.Builder,
    private val properties: Auth0ManagementProperties,
    private val clock: Clock,
) : UserDirectory {
    @Autowired
    constructor(builder: RestClient.Builder, properties: Auth0ManagementProperties) : this(builder, properties, Clock.systemUTC())

    private val client = ClientCredentialsToken.withoutForwardedToken(builder)

    private val token =
        ClientCredentialsToken(
            client = client,
            service = SERVICE,
            tokenUri = "${properties.baseUrl}/oauth/token",
            clientId = properties.clientId,
            clientSecret = properties.clientSecret,
            audience = "${properties.baseUrl}/api/v2/",
            clock = clock,
        )

    override fun search(
        name: String?,
        excludedUserId: String,
        page: Int,
        pageSize: Int,
    ): UserSearchResult {
        val query = Auth0Query.users(name, excludedUserId)
        val result =
            authorized {
                upstream(SERVICE) {
                    client
                        .get()
                        .uri(
                            "${properties.baseUrl}/api/v2/users?q={q}&search_engine=v3&page={page}&per_page={perPage}" +
                                "&include_totals=true&fields={fields}&include_fields=true",
                            query,
                            page,
                            pageSize,
                            FIELDS,
                        ).headers { it.setBearerAuth(accessToken()) }
                        .retrieve()
                        .body<Auth0UserPage>()
                }
            }
        // The query already leaves the caller out; this only guards against a query Auth0 reads differently
        val users = result.users.filter { it.userId != excludedUserId }
        return UserSearchResult(result.total, users.map { UserSummary(it.userId, it.displayName()) })
    }

    override fun exists(userId: String): Boolean =
        try {
            authorized {
                upstream(SERVICE) {
                    client
                        .get()
                        .uri("${properties.baseUrl}/api/v2/users/{id}?fields=user_id&include_fields=true", userId)
                        .headers { it.setBearerAuth(accessToken()) }
                        .retrieve()
                        .toBodilessEntity()
                }
            }
            true
        } catch (e: UpstreamException) {
            if (e.cause is HttpClientErrorException.NotFound) false else throw e
        }

    /** A 401 means the token is no longer good (revoked, rotated secret): forget it so the next call gets another. */
    private fun <T> authorized(call: () -> T): T =
        try {
            call()
        } catch (e: UpstreamException) {
            if (e.cause is HttpClientErrorException.Unauthorized) token.invalidate()
            throw e
        }

    private fun accessToken(): String {
        requireConfigured()
        return token.get()
    }

    private fun requireConfigured() {
        if (!properties.configured) {
            throw UpstreamException(SERVICE, "not configured: set AUTH0_DOMAIN, AUTH0_M2M_CLIENT_ID and AUTH0_M2M_CLIENT_SECRET")
        }
    }

    private data class Auth0UserPage(
        val total: Long,
        val users: List<Auth0User>,
    )

    private data class Auth0User(
        @param:JsonProperty("user_id") val userId: String,
        val name: String? = null,
        val nickname: String? = null,
        val email: String? = null,
    ) {
        fun displayName() = listOf(name, nickname, email).firstOrNull { !it.isNullOrBlank() } ?: userId
    }

    companion object {
        const val SERVICE = "Auth0 Management API"
        private const val FIELDS = "user_id,name,nickname,email"
    }
}

/** Lucene queries for the user search of Auth0 (search engine v3). */
internal object Auth0Query {
    // Characters with a meaning in the query syntax, plus whitespace (a term can't contain it unescaped)
    private const val SPECIAL = "+-&|!(){}[]^\"~*?:\\/"

    /** Prefix match on name, nickname or email, leaving [excludedUserId] out. */
    fun users(
        name: String?,
        excludedUserId: String,
    ): String {
        val exclusion = "NOT user_id:${quote(excludedUserId)}"
        val term = name?.trim().orEmpty()
        if (term.isEmpty()) return exclusion
        val prefix = escape(term) + "*"
        return "(name:$prefix OR nickname:$prefix OR email:$prefix) AND $exclusion"
    }

    /** Escapes a term so it's matched literally (it can still take a trailing `*`). */
    fun escape(term: String): String =
        buildString {
            for (c in term) {
                if (c in SPECIAL || c.isWhitespace()) append('\\')
                append(c)
            }
        }

    /** An exact phrase: only `"` and `\` need escaping inside quotes. */
    fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
