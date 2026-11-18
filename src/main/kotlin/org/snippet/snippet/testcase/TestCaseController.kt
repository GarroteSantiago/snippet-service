package org.snippet.snippet.testcase

import jakarta.validation.Valid
import org.snippet.snippet.common.security.CurrentUser
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** `/snippets/{snippetId}/tests` of `contracts/snippet-service.openapi.yaml` (tag `tests`). */
@RestController
@RequestMapping("/snippets/{snippetId}/tests")
class TestCaseController(
    private val service: TestCaseService,
) {
    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable snippetId: UUID,
    ): List<TestCaseResponse> = service.list(CurrentUser.from(jwt), snippetId)

    // No Location: the contract has no endpoint to get a single test
    @PostMapping
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable snippetId: UUID,
        @Valid @RequestBody request: TestCaseRequest,
    ): ResponseEntity<TestCaseResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.create(CurrentUser.from(jwt), snippetId, request))

    @PostMapping("/run")
    fun runAdHoc(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable snippetId: UUID,
        @Valid @RequestBody request: TestCaseRequest,
    ): TestRunResult = service.runAdHoc(CurrentUser.from(jwt), snippetId, request)

    @PutMapping("/{testId}")
    fun update(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable snippetId: UUID,
        @PathVariable testId: UUID,
        @Valid @RequestBody request: TestCaseRequest,
    ): TestCaseResponse = service.update(CurrentUser.from(jwt), snippetId, testId, request)

    @DeleteMapping("/{testId}")
    fun delete(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable snippetId: UUID,
        @PathVariable testId: UUID,
    ): ResponseEntity<Unit> {
        service.delete(CurrentUser.from(jwt), snippetId, testId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{testId}/run")
    fun run(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable snippetId: UUID,
        @PathVariable testId: UUID,
    ): TestRunResult = service.run(CurrentUser.from(jwt), snippetId, testId)
}
