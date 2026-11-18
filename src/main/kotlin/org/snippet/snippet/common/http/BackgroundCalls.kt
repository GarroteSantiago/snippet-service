package org.snippet.snippet.common.http

import org.springframework.stereotype.Component

/**
 * The only way work without a user request (the `snippet:results` consumer, the `parserVersion`
 * check) calls the Runner or the Asset Service.
 *
 * KNOWN GAP: those calls have no user JWT to forward, so for now they go out WITHOUT an
 * `Authorization` header and a service that requires a token (the Runner does) answers 401,
 * which comes back as [org.snippet.snippet.common.error.UpstreamException]. The fix belongs
 * here: get an Auth0 machine-to-machine token (client credentials, cached until it expires)
 * and put it in the security context, or in the request, for the duration of [call].
 */
@Component
class BackgroundCalls {
    /** Runs [block], which calls other services on behalf of the system, not of a user. */
    fun <T> call(block: () -> T): T = block()
}
