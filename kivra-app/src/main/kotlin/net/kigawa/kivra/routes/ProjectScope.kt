package net.kigawa.kivra.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import net.kigawa.kivra.auth.ReadScope
import net.kigawa.kivra.db.ProjectFilter
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.UNASSIGNED_PROJECT_ID

/**
 * The project a new entity is created under (ADR 0022, PR-C). Every creation API takes a `projectId`, and it is required: a missing or blank
 * one is 400, a malformed one is 400, and an id that names no project is 404 (the same as the project routes). There is no default project.
 *
 * Errors use fixed text and never echo the request value.
 */
internal sealed interface ProjectScope {
    data class Assigned(val projectId: String) : ProjectScope
    data class Refused(val status: HttpStatusCode, val error: String) : ProjectScope
}

internal const val PROJECT_REQUIRED = "projectId is required"
internal const val PROJECT_INVALID = "projectId must be a project id"
internal const val PROJECT_NOT_FOUND = "project not found"

/** The shape of a project id (a ULID). */
internal val PROJECT_ID_SHAPE = Regex("[0-9A-Z]{26}")

/** The text of a list's `projectId` query that is not a project id nor `unassigned`. Fixed; the value is never echoed. */
internal const val PROJECT_FILTER_INVALID = "projectId must be a project id or unassigned"

/**
 * The project a list is narrowed to, from its `projectId` query (ADR 0022 PR-D). Absent or blank: every row (the list as before). `unassigned`:
 * the rows with no project. A project id: the rows of that project. Null for anything else (the caller answers 400). An unknown project id is
 * not an error here: its list is empty.
 */
internal fun projectFilterOf(raw: String?): ProjectFilter? = when {
    raw.isNullOrBlank() -> ProjectFilter.All
    raw == UNASSIGNED_PROJECT_ID -> ProjectFilter.Unassigned
    PROJECT_ID_SHAPE.matches(raw) -> ProjectFilter.Only(raw)
    else -> null
}

/** Checks [projectId] for a new entity: required, well formed, and an existing project. */
internal fun ProjectRepository.scopeOf(projectId: String?): ProjectScope {
    if (projectId.isNullOrBlank()) return ProjectScope.Refused(HttpStatusCode.BadRequest, PROJECT_REQUIRED)
    if (!PROJECT_ID_SHAPE.matches(projectId)) return ProjectScope.Refused(HttpStatusCode.BadRequest, PROJECT_INVALID)
    if (get(projectId) == null) return ProjectScope.Refused(HttpStatusCode.NotFound, PROJECT_NOT_FOUND)
    return ProjectScope.Assigned(projectId)
}

/**
 * The project of an alias route (`/api/projects/<id>/...`), read from the request path only, or null on any other path (an old route). Ktor's
 * `call.parameters` puts the query first and drops the path's value when the query has the same name, so a `?projectId=` would win there.
 * The id is checked by its shape where it is used; a path that is not a project's is a 404 there, never the old route's answer.
 */
internal fun ApplicationCall.pathProjectId(): String? {
    val segments = request.path().split('/')
    // "", "api", "projects", "<id>", ... for the alias routes.
    return if (segments.size >= 5 && segments[1] == "api" && segments[2] == "projects") segments[3] else null
}

/** The body `projectId` of an alias route (`/api/projects/{projectId}/...`) must equal the path's project. Fixed text; never echoed. */
internal const val PROJECT_MISMATCH = "projectId must be the project in the path"

/**
 * The scope of a creation on an alias route (ADR 0022 PR-E): the path's project is the scope. A body `projectId` is optional there, but when it
 * is given it must equal the path (400). A path that names no project is 404, as in [scopeOf]. The body is checked after the path.
 */
internal fun ProjectRepository.scopeOfPath(pinned: String, bodyProjectId: String?): ProjectScope {
    if (!projectExistsForPath(pinned)) return ProjectScope.Refused(HttpStatusCode.NotFound, PROJECT_NOT_FOUND)
    if (bodyProjectId != null && bodyProjectId != pinned) return ProjectScope.Refused(HttpStatusCode.BadRequest, PROJECT_MISMATCH)
    return ProjectScope.Assigned(pinned)
}

/** True when the path's [pinned] id names a project. A malformed id names none. */
internal fun ProjectRepository.projectExistsForPath(pinned: String): Boolean =
    PROJECT_ID_SHAPE.matches(pinned) && get(pinned) != null

/** The narrowing of a list: the rows of one project, or every row (no project named at all). */
internal sealed interface ListScope {
    data class Filtered(val filter: ProjectFilter) : ListScope
    data class Refused(val status: HttpStatusCode, val error: String) : ListScope
}

/**
 * The narrowing of a list (ADR 0022 PR-D/PR-E). On an old route ([pinned] null) it is the `projectId` query, as before: absent or
 * `unassigned` or a project id, anything else 400. On an alias route the path's project is the narrowing (it must exist: the caller checks
 * that first, see [projectExistsForPath]); a `projectId` query that is given and differs is 400.
 */
internal fun listScopeOf(pinned: String?, query: String?): ListScope {
    if (pinned == null) {
        val filter = projectFilterOf(query) ?: return ListScope.Refused(HttpStatusCode.BadRequest, PROJECT_FILTER_INVALID)
        return ListScope.Filtered(filter)
    }
    if (!query.isNullOrBlank() && query != pinned) return ListScope.Refused(HttpStatusCode.BadRequest, PROJECT_MISMATCH)
    return ListScope.Filtered(ProjectFilter.Only(pinned))
}

/**
 * The narrowing of a list under [this] read scope (ADR 0022 PR-D3): the filter the list runs with, so the rows it may not read are left out in
 * SQL, before its limit. A refused read reads nothing. [ReadScope.All] keeps the filter as it is (the list as before).
 */
internal fun ReadScope.narrow(filter: ProjectFilter): ProjectFilter = when (this) {
    ReadScope.All -> filter
    ReadScope.Refused -> ProjectFilter.Within(emptySet())
    is ReadScope.Members -> when (filter) {
        ProjectFilter.All -> ProjectFilter.Within(projectIds)
        ProjectFilter.Unassigned -> ProjectFilter.Within(emptySet())
        is ProjectFilter.Only -> ProjectFilter.Within(projectIds.filter { it == filter.projectId }.toSet())
        is ProjectFilter.Within -> ProjectFilter.Within(filter.projectIds intersect projectIds)
    }
}

/**
 * The header of an old route that takes a `projectId` (ADR 0022 PR-E): `Deprecation: true` and a `successor-version` link to the alias. The
 * answer itself is unchanged. [successor] is the alias's path with `{projectId}` as its placeholder; the placeholder is filled with the query's
 * project id when it is a project id, and otherwise stays encoded as a template.
 */
internal fun ApplicationCall.deprecatedBy(successor: String, query: String?) {
    val id = query?.takeIf { PROJECT_ID_SHAPE.matches(it) } ?: "%7BprojectId%7D"
    response.headers.append("Deprecation", "true")
    response.headers.append("Link", "<" + successor.replace("{projectId}", id) + ">; rel=\"successor-version\"")
}
