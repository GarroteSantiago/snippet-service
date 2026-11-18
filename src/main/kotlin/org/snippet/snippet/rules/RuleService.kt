package org.snippet.snippet.rules

import org.snippet.snippet.clients.runner.RuleConfig
import org.snippet.snippet.clients.runner.RuleType
import org.snippet.snippet.clients.runner.RunnerClient
import org.snippet.snippet.common.error.BadRequestException
import org.snippet.snippet.processing.SnippetJobs
import org.springframework.stereotype.Service

/** `Rule` in the public contract. The id is the rule name, the Runner's stable identifier. */
data class Rule(
    val id: String,
    val name: String,
    val isActive: Boolean,
    val value: Any? = null,
)

/** A rule as the UI sends it; the id is ignored (the name is the identifier). [isActive] is required (null: missing). */
data class RuleInput(
    val id: String? = null,
    val name: String,
    val isActive: Boolean?,
    val value: Any? = null,
)

/** The rules in effect for a user: the ones they saved, or the Runner's defaults. */
@Service
class RuleStore(
    private val repository: RuleRepository,
    private val runner: RunnerClient,
) {
    fun rulesOf(
        ownerId: String,
        type: RuleType,
    ): List<RuleConfig> = repository.find(ownerId, type) ?: runner.defaultRules(type).map { it.copy(description = null) }
}

@Service
class RuleService(
    private val store: RuleStore,
    private val repository: RuleRepository,
    private val runner: RunnerClient,
    private val jobs: SnippetJobs,
) {
    fun get(
        userId: String,
        type: RuleType,
    ): List<Rule> = store.rulesOf(userId, type).map { it.toRule() }

    /**
     * Validates and saves the rules, then reprocesses the user's snippets in the background
     * (`contracts/redis-streams.md`): lint sends them to `pending` and publishes a LINT each,
     * format publishes a FORMAT each.
     */
    fun update(
        userId: String,
        type: RuleType,
        input: List<RuleInput>,
    ): List<Rule> {
        val rules = validate(type, input)
        repository.save(userId, type, rules)
        when (type) {
            RuleType.LINT -> jobs.lintAllOf(userId, rules)
            RuleType.FORMAT -> jobs.formatAllOf(userId, rules)
        }
        return rules.map { it.toRule() }
    }

    private fun validate(
        type: RuleType,
        input: List<RuleInput>,
    ): List<RuleConfig> {
        input.groupBy { it.name }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            throw BadRequestException("Rule '$it' appears more than once")
        }
        input.firstOrNull { it.isActive == null }?.let { throw BadRequestException("Rule '${it.name}' needs isActive") }
        val known = runner.defaultRules(type).map { it.name }.toSet()
        input.firstOrNull { it.name !in known }?.let { throw BadRequestException("Unknown ${type.path} rule '${it.name}'") }
        input.firstOrNull { it.value != null && it.value !is String && it.value !is Number }?.let {
            throw BadRequestException("The value of rule '${it.name}' must be a string, a number or null")
        }
        val rules = input.map { RuleConfig(it.name, it.isActive!!, it.value) }
        // Ranges, choices and incompatible pairs are the Runner's business
        runner.validateRules(type, rules, runner.versions().latest)
        return rules
    }

    private fun RuleConfig.toRule() = Rule(name, name, isActive, value)
}
