package net.kigawa.kivra.routes

import io.ktor.http.HttpStatusCode
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
