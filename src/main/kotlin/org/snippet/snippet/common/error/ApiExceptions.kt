package org.snippet.snippet.common.error

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

/** Extends Spring's handler so validation and binding errors also come out as Problem Details (400). */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    @ExceptionHandler(ApiException::class)
    fun handleApiException(exception: ApiException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(exception.status, exception.message ?: exception.status.reasonPhrase)
}
