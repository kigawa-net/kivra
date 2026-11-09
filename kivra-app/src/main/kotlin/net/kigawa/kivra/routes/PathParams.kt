package net.kigawa.kivra.routes

import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.RoutingRequest

/**
 * A path parameter (`{name}` in the template of the matched route), read from that route's own captures only.
 *
 * `call.parameters` is the query first and the path after, so a `?name=` with the same name wins over the path's value (the path is
 * shadowed: `GET /api/projects/A/members?projectId=B` would answer for B). `request.pathVariables` holds the captures of the matched
 * route and never the query, so it is always the path's value. Null when the route has no such capture; the handler answers 404 then.
 *
 * Every path parameter in `routes/` is read through this function. The architecture test (PathParamArchitectureTest) keeps
 * `call.parameters` out of `routes/`.
 */
internal fun ApplicationCall.pathParam(name: String): String? = (request as? RoutingRequest)?.pathVariables?.get(name)
