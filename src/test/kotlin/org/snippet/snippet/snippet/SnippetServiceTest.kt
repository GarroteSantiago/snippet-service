package org.snippet.snippet.snippet

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.snippet.snippet.clients.auth0.UserSummary
import org.snippet.snippet.clients.permission.RelationFilter
import org.snippet.snippet.clients.permission.Role
import org.snippet.snippet.common.error.BadRequestException
import org.snippet.snippet.common.error.ForbiddenException
import org.snippet.snippet.common.error.InvalidCodeException
import org.snippet.snippet.common.error.NotFoundException
import org.snippet.snippet.common.error.UpstreamException
import org.snippet.snippet.common.security.CurrentUser
import org.snippet.snippet.language.LanguageCatalog
import org.snippet.snippet.language.LanguageProperties
import org.snippet.snippet.snippet.SnippetService.Companion.CONTENT
import org.snippet.snippet.snippet.SnippetService.Companion.FORMATTED
import org.snippet.snippet.support.FakePermissions
import org.snippet.snippet.support.FakeRunner
import org.snippet.snippet.support.FakeUsers
import org.snippet.snippet.support.ProcessingFixture
import org.springframework.data.domain.Sort
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnippetServiceTest {
    private val processing = ProcessingFixture()
    private val repository = processing.repository
    private val permissions = FakePermissions()
    private val runner = processing.runner
    private val assets = processing.assets
    private val users = FakeUsers(UserSummary("auth0|alice", "Alice"), UserSummary("auth0|bob", "Bob"), UserSummary("auth0|carol", "Carol"))
    private val service =
        SnippetService(repository, permissions, runner, assets, LanguageCatalog(runner, LanguageProperties()), processing.jobs, users)

    private val alice = CurrentUser("auth0|alice", "Alice")
    private val bob = CurrentUser("auth0|bob", "Bob")

    private fun create(
        name: String = "hello",
        content: String = "println(\"hi\");",
        user: CurrentUser = alice,
    ) = service.create(user, CreateSnippetRequest(name, "says hi", "printscript", "1.1", "ps", content))

    private fun shareWithBob(id: UUID) {
        permissions.roles[id to bob.id] = Role.READ
    }

    @Test
    fun `create stores metadata, content and the owner, and starts pending`() {
        val created = create()

        assertEquals(Compliance.PENDING, created.compliance)
        assertEquals("Alice", created.author)
        assertEquals("auth0|alice", created.authorId)
        assertEquals(Relation.OWNED, created.relation)
        assertEquals("println(\"hi\");", created.content)
        assertEquals("println(\"hi\");", assets.stored[CONTENT to created.id.toString()])
        assertEquals(Role.OWNER, permissions.roles[created.id to alice.id])
        assertEquals("auth0|alice", repository.snippets.getValue(created.id).ownerId)
    }

    @Test
    fun `create rejects invalid code with its diagnostics and stores nothing`() {
        val error = assertThrows<InvalidCodeException> { create(content = "let x = !!;") }

        assertEquals(listOf(FakeRunner.DIAGNOSTIC), error.diagnostics)
        assertTrue(repository.snippets.isEmpty())
        assertTrue(assets.stored.isEmpty())
        assertTrue(permissions.roles.isEmpty())
    }

    @Test
    fun `create rejects a language, version or extension the runner doesn't support`() {
        val request = CreateSnippetRequest("x", null, "printscript", "1.1", "ps", "println(1);")
        assertThrows<BadRequestException> { service.create(alice, request.copy(language = "python")) }
        assertThrows<BadRequestException> { service.create(alice, request.copy(version = "9.9")) }
        assertThrows<BadRequestException> { service.create(alice, request.copy(extension = "py")) }
        assertTrue(repository.snippets.isEmpty())
    }

    @Test
    fun `create undoes everything when granting the owner fails`() {
        permissions.failGrant = true

        assertThrows<UpstreamException> { create() }

        assertTrue(repository.snippets.isEmpty())
        assertTrue(assets.stored.isEmpty())
    }

    @Test
    fun `create stops before calling other services when the database fails`() {
        repository.failSave = true

        assertThrows<IllegalStateException> { create() }

        assertTrue(assets.stored.isEmpty())
        assertTrue(permissions.roles.isEmpty())
    }

    @Test
    fun `create undoes the metadata when storing the content fails`() {
        assets.failPut = true

        assertThrows<UpstreamException> { create() }

        assertTrue(repository.snippets.isEmpty())
        assertTrue(permissions.roles.isEmpty())
    }

    @Test
    fun `get returns content and lint violations to whoever can read`() {
        val created = create()
        shareWithBob(created.id)

        val seen = service.get(bob, created.id)

        assertEquals("println(\"hi\");", seen.content)
        assertEquals(emptyList(), seen.lintViolations)
        assertEquals(Relation.SHARED, seen.relation)
        assertEquals("Alice", seen.author)
    }

    @Test
    fun `a snippet the user has no permission on looks the same as one that doesn't exist`() {
        val created = create()

        assertThrows<NotFoundException> { service.get(bob, created.id) }
        assertThrows<NotFoundException> { service.get(alice, UUID.randomUUID()) }
        assertThrows<NotFoundException> { service.delete(bob, created.id) }
    }

    @Test
    fun `only the owner can update or delete`() {
        val created = create()
        shareWithBob(created.id)

        assertThrows<ForbiddenException> { service.update(bob, created.id, UpdateSnippetRequest(name = "mine")) }
        assertThrows<ForbiddenException> { service.delete(bob, created.id) }
        assertEquals("hello", repository.snippets.getValue(created.id).name)
    }

    @Test
    fun `update changes only the fields that come`() {
        val created = create()
        repository.snippets.getValue(created.id).compliance = Compliance.COMPLIANT

        val updated = service.update(alice, created.id, UpdateSnippetRequest(name = "renamed"))

        assertEquals("renamed", updated.name)
        assertEquals("says hi", updated.description)
        assertEquals("println(\"hi\");", updated.content)
        // Same code: the lint result still holds
        assertEquals(Compliance.COMPLIANT, updated.compliance)
    }

    @Test
    fun `new content is stored and sends the snippet back to pending`() {
        val created = create()
        repository.snippets.getValue(created.id).apply {
            compliance = Compliance.NOT_COMPLIANT
            lintViolations = mutableListOf(StoredViolation("no-snake-case", "bad name", 1, 5))
        }

        val updated = service.update(alice, created.id, UpdateSnippetRequest(content = "println(2);"))

        assertEquals(Compliance.PENDING, updated.compliance)
        assertEquals(emptyList(), updated.lintViolations)
        assertEquals("println(2);", assets.stored[CONTENT to created.id.toString()])
    }

    @Test
    fun `invalid new content changes nothing`() {
        val created = create()

        assertThrows<InvalidCodeException> {
            service.update(alice, created.id, UpdateSnippetRequest(name = "renamed", content = "!!"))
        }

        assertEquals("hello", repository.snippets.getValue(created.id).name)
        assertEquals("println(\"hi\");", assets.stored[CONTENT to created.id.toString()])
    }

    @Test
    fun `changing the version validates the current content again`() {
        val created = create(content = "let x = 1;")
        assets.stored[CONTENT to created.id.toString()] = "!!"

        assertThrows<InvalidCodeException> { service.update(alice, created.id, UpdateSnippetRequest(version = "1.0")) }
        assertThrows<BadRequestException> { service.update(alice, created.id, UpdateSnippetRequest(version = "3.0")) }
        assertEquals("1.1", repository.snippets.getValue(created.id).version)
    }

    @Test
    fun `update puts the metadata back when storing the content fails`() {
        val created = create()
        assets.failPut = true

        assertThrows<UpstreamException> {
            service.update(alice, created.id, UpdateSnippetRequest(name = "renamed", content = "println(2);"))
        }

        val snippet = repository.snippets.getValue(created.id)
        assertEquals("hello", snippet.name)
        assertEquals(created.updatedAt, snippet.updatedAt)
    }

    @Test
    fun `delete removes metadata, content, formatted content and permissions`() {
        val created = create()
        shareWithBob(created.id)
        assets.stored[FORMATTED to created.id.toString()] = "formatted"

        service.delete(alice, created.id)

        assertTrue(repository.snippets.isEmpty())
        assertTrue(assets.stored.isEmpty())
        assertTrue(permissions.roles.isEmpty())
    }

    @Test
    fun `list shows the relation of each snippet and no content`() {
        val mine = create(name = "mine")
        val theirs = create(name = "theirs", user = bob)
        permissions.roles[theirs.id to alice.id] = Role.READ

        val page = service.list(alice, SnippetQuery(page = 0, pageSize = 10))

        assertEquals(2, page.count)
        val byId = page.snippets.associateBy { it.id }
        assertEquals(Relation.OWNED, byId.getValue(mine.id).relation)
        assertEquals(Relation.SHARED, byId.getValue(theirs.id).relation)
        assertEquals("Bob", byId.getValue(theirs.id).author)
        assertNull(byId.getValue(mine.id).content)
        assertNull(byId.getValue(mine.id).lintViolations)
    }

    @Test
    fun `list sorts as asked, breaking ties by id`() {
        create()

        service.list(alice, SnippetQuery(page = 2, pageSize = 5, sort = SortField.COMPLIANCE, order = SortOrder.DESC))

        val pageable = repository.lastPageable!!
        assertEquals(2, pageable.pageNumber)
        assertEquals(5, pageable.pageSize)
        assertEquals(listOf(Sort.Order.desc("compliance"), Sort.Order.asc("id")), pageable.sort.toList())
    }

    @Test
    fun `list sorts names ignoring case`() {
        create()

        service.list(alice, SnippetQuery())

        assertTrue(
            repository.lastPageable!!
                .sort
                .getOrderFor("name")!!
                .isIgnoreCase,
        )
    }

    @Test
    fun `list without accessible snippets doesn't query the database`() {
        create(user = bob)

        val page = service.list(alice, SnippetQuery(relation = RelationFilter.SHARED))

        assertEquals(0, page.count)
        assertEquals(emptyList(), page.snippets)
        assertNull(repository.lastPageable)
    }

    @Test
    fun `download names the file after the snippet`() {
        val created = create(name = "hello")
        shareWithBob(created.id)

        val file = service.download(bob, created.id, formatted = false)

        assertEquals("hello.ps", file.filename)
        assertEquals("println(\"hi\");", file.content)
    }

    @Test
    fun `formatted download is 404 until there is a formatted version`() {
        val created = create()

        assertThrows<NotFoundException> { service.download(alice, created.id, formatted = true) }

        assets.stored[FORMATTED to created.id.toString()] = "println(\"hi\");\n"
        assertEquals("println(\"hi\");\n", service.download(alice, created.id, formatted = true).content)
    }

    @Test
    fun `missing content is an upstream error, not a missing snippet`() {
        val created = create()
        assets.stored.clear()

        assertThrows<UpstreamException> { service.get(alice, created.id) }
    }

    @Test
    fun `share gives read to the user and returns the snippet with its content`() {
        val created = create()

        val shared = service.share(alice, created.id, bob.id)

        assertEquals(Role.READ, permissions.roles[created.id to bob.id])
        assertEquals(created.id, shared.id)
        assertEquals(Relation.OWNED, shared.relation)
        assertEquals("println(\"hi\");", shared.content)
        assertEquals(Relation.SHARED, service.get(bob, created.id).relation)
    }

    @Test
    fun `sharing again with the same user is a no-op that still succeeds`() {
        val created = create()
        service.share(alice, created.id, bob.id)

        val again = service.share(alice, created.id, bob.id)

        assertEquals(created.id, again.id)
        assertEquals(Role.READ, permissions.roles[created.id to bob.id])
        assertEquals(2, permissions.roles.size)
    }

    @Test
    fun `only the owner can share`() {
        val created = create()
        shareWithBob(created.id)

        assertThrows<ForbiddenException> { service.share(bob, created.id, "auth0|carol") }
        assertThrows<NotFoundException> { service.share(CurrentUser("auth0|carol", "Carol"), created.id, bob.id) }
        assertThrows<NotFoundException> { service.share(alice, UUID.randomUUID(), bob.id) }
        assertNull(permissions.roles[created.id to "auth0|carol"])
    }

    @Test
    fun `sharing with yourself or with an unknown user is a bad request`() {
        val created = create()

        assertThrows<BadRequestException> { service.share(alice, created.id, alice.id) }
        assertThrows<BadRequestException> { service.share(alice, created.id, "auth0|nobody") }
        assertEquals(1, permissions.roles.size)
    }

    @Test
    fun `share is an upstream error when auth0 or the permission service fail`() {
        val created = create()

        users.fail = true
        assertThrows<UpstreamException> { service.share(alice, created.id, bob.id) }
        users.fail = false
        permissions.failGrant = true
        assertThrows<UpstreamException> { service.share(alice, created.id, bob.id) }
        assertNull(permissions.roles[created.id to bob.id])
    }
}
