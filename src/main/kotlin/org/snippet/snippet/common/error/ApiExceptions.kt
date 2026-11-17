package org.snippet.snippet.common.error

import org.slf4j.LoggerFactory
import org.snippet.snippet.clients.runner.Diagnostic
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/** Errors the API returns on purpose. Rendered as Problem Details (RFC 9457). */
open class ApiException(
    val status: HttpStatus,
    message: String,
) : RuntimeException(message)

class NotFoundException(
    message: String,
) : ApiException(HttpStatus.NOT_FOUND, message)

class ForbiddenException(
    message: String = "You don't have permission on this resource",
) : ApiException(HttpStatus.FORBIDDEN, message)

class ConflictException(
    message: String,
) : ApiException(HttpStatus.CONFLICT, message)

class BadRequestException(
    message: String,
) : ApiException(HttpStatus.BAD_REQUEST, message)

/** The parser rejected the code: 422 with the parser's `diagnostics`, as the contracts say. */
class InvalidCodeException(
    val diagnostics: List<Diagnostic>,
    message: String = "The code is not valid for this version",
) : ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message)

/** A service we depend on failed or answered something unexpected. Rendered as 502. */
class UpstreamException(
    val service: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException("$service: $message", cause)

/** Extends Spring's handler so validation and binding errors also come out as Problem Details (400). */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApiException(exception: ApiException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(exception.status, exception.message ?: exception.status.reasonPhrase).apply {
            if (exception is InvalidCodeException) setProperty("diagnostics", exception.diagnostics)
        }

    @ExceptionHandler(UpstreamException::class)
    fun handleUpstreamException(exception: UpstreamException): ProblemDetail {
        log.error("Upstream call failed: {}", exception.message, exception)
        // The detail names the service but not its internals
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "${exception.service} is not available")
    }
}
