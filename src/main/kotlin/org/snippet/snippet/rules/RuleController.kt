package org.snippet.snippet.rules

import org.snippet.snippet.clients.runner.RuleType
import org.snippet.snippet.common.error.BadRequestException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** `/rules/{ruleType}` of `contracts/snippet-service.openapi.yaml` (US-11, US-12, US-14, US-15). */
@RestController
@RequestMapping("/rules/{ruleType}")
class RuleController(
    private val service: RuleService,
) {
    @GetMapping
    fun get(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable ruleType: String,
    ): List<Rule> = service.get(jwt.subject, parse(ruleType))

    /** 202: the rules are saved; reprocessing the snippets goes on in the background. */
    @PutMapping
    fun update(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable ruleType: String,
        @RequestBody rules: List<RuleInput>,
    ): ResponseEntity<List<Rule>> = ResponseEntity.status(HttpStatus.ACCEPTED).body(service.update(jwt.subject, parse(ruleType), rules))

    private fun parse(ruleType: String): RuleType =
        RuleType.entries.find { it.path == ruleType } ?: throw BadRequestException("There are no '$ruleType' rules (format or lint)")
}
