package net.kigawa.kivra.routes

import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.kivra.appModule

private object UiTestResources

private fun resource(name: String): String =
    UiTestResources::class.java.getResourceAsStream("/ui/$name")?.use { it.readBytes().decodeToString() }
        ?: error("ui/$name is missing from the test classpath")

private val indexHtml by lazy { resource("index.html") }
private val appJs by lazy { resource("app.js") }
private val appCss by lazy { resource("app.css") }

private val EXPECTED_CSP =
    "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; " +
        "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

/** Parses a CSP header into directive name to source list. */
private fun parseCsp(csp: String): Map<String, List<String>> =
    csp.split(';').map { it.trim() }.filter { it.isNotEmpty() }.associate { directive ->
        val parts = directive.split(Regex("\\s+"))
        parts[0] to parts.drop(1)
    }

class UiRoutesTest {
    private fun assertSecurityHeaders(response: HttpResponse) {
        assertEquals(EXPECTED_CSP, response.headers["Content-Security-Policy"])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("no-referrer", response.headers["Referrer-Policy"])
        assertEquals("no-store", response.headers["Cache-Control"])
    }

    @Test
    fun `ui pages are served with the right content types and security headers`() = testApplication {
        application { routing { configureUiRoutes() } }

        for (path in listOf("/ui", "/ui/")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.OK, response.status, path)
            assertEquals("text/html; charset=utf-8", response.headers["Content-Type"], path)
            assertSecurityHeaders(response)
        }

        val js = client.get("/ui/app.js")
        assertEquals(HttpStatusCode.OK, js.status)
        assertEquals("text/javascript; charset=utf-8", js.headers["Content-Type"])
        assertSecurityHeaders(js)

        val css = client.get("/ui/app.css")
        assertEquals(HttpStatusCode.OK, css.status)
        assertEquals("text/css; charset=utf-8", css.headers["Content-Type"])
        assertSecurityHeaders(css)
    }

    @Test
    fun `served html is the bundled index and references only same-origin assets`() = testApplication {
        application { routing { configureUiRoutes() } }

        assertEquals(indexHtml, client.get("/ui").bodyAsText())
        assertEquals(appJs, client.get("/ui/app.js").bodyAsText())
        assertEquals(appCss, client.get("/ui/app.css").bodyAsText())
    }

    @Test
    fun `the csp grants only same-origin scripts, styles and images, with no inline code, eval, hashes or third parties`() {
        val csp = parseCsp(UI_CONTENT_SECURITY_POLICY)
        // The directives the UI depends on, exactly. Anything else stays denied by default-src 'none'.
        assertEquals(listOf("'none'"), csp["default-src"])
        assertEquals(listOf("'self'"), csp["script-src"])
        assertEquals(listOf("'self'"), csp["style-src"])
        assertEquals(listOf("'self'"), csp["img-src"])
        assertEquals(listOf("'self'"), csp["connect-src"])
        // No directive may allow inline code, eval, hashes or nonces, wildcards, schemes, or third-party hosts.
        val forbidden = listOf("'unsafe-inline'", "'unsafe-eval'", "'unsafe-hashes'", "'strict-dynamic'", "*", "data:", "blob:")
        for ((directive, sources) in csp) {
            for (source in sources) {
                assertFalse(source.startsWith("'sha") || source.startsWith("'nonce-"), "$directive: $source")
                assertFalse(source in forbidden, "$directive must not allow $source")
                assertFalse(source.contains("://") || source.endsWith(":"), "$directive must not allow $source")
            }
        }
        assertFalse(UI_CONTENT_SECURITY_POLICY.contains("cloudflare", ignoreCase = true))
    }

    @Test
    fun `no external URLs, CDN references or inline scripts or styles`() {
        for ((name, text) in listOf("index.html" to indexHtml, "app.js" to appJs, "app.css" to appCss)) {
            assertFalse(text.contains("http://"), "$name must not contain http://")
            assertFalse(text.contains("https://"), "$name must not contain https://")
            assertFalse(text.contains("//cdn", ignoreCase = true), "$name must not reference a CDN")
            assertFalse(text.contains("@import"), "$name must not use @import")
        }
        // The only script and stylesheet are same-origin files; there is no inline code for the CSP to block.
        assertEquals(1, Regex("<script\\b").findAll(indexHtml).count())
        assertContains(indexHtml, "<script src=\"/ui/app.js\" defer></script>")
        assertEquals(1, Regex("<link\\b").findAll(indexHtml).count())
        assertContains(indexHtml, "<link rel=\"stylesheet\" href=\"/ui/app.css\">")
        assertFalse(Regex("<script(?![^>]*\\bsrc=)[^>]*>").containsMatchIn(indexHtml), "no inline <script>")
        assertFalse(Regex("<style\\b").containsMatchIn(indexHtml), "no inline <style>")
        assertFalse(Regex("\\sstyle=\"").containsMatchIn(indexHtml), "no inline style attributes")
    }

    @Test
    fun `script does not use DOM sinks that would interpret server data as markup or code`() {
        val forbidden = listOf(
            "innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function",
            "javascript:", "srcdoc", "execCommand",
        )
        for (token in forbidden) {
            assertFalse(appJs.contains(token), "app.js must not use $token")
            assertFalse(indexHtml.contains(token), "index.html must not use $token")
        }
        // No event-handler attributes set by string, e.g. setAttribute('onclick', ...).
        assertFalse(Regex("setAttribute\\(\\s*['\"]on", RegexOption.IGNORE_CASE).containsMatchIn(appJs))
        // Only fixed attribute names are set (the navigation sets aria-current and aria-label, with fixed names too).
        assertFalse(Regex("setAttribute\\(\\s*['\"](?!href['\"]|type['\"]|aria-current['\"]|aria-label['\"])").containsMatchIn(appJs))
        // fetch targets are same-origin paths only. Every call is checked, whatever the quote style (', ", or a template literal).
        val fetchTargets = Regex("(?<!function )apiFetch\\(\\s*([^,)]*)").findAll(appJs).map { it.groupValues[1].trim() }.toList()
        assertTrue(fetchTargets.size >= 5, "fetch calls: $fetchTargets")
        assertTrue(
            fetchTargets.all { it.startsWith("'/api/") || it == "'/health'" },
            "every fetch target must be a literal path under /api/ or /health: $fetchTargets",
        )
        assertFalse(appJs.contains("`"), "app.js must not use template literals")
        // Authorization is added by oauth2-proxy, never by the page.
        assertFalse(appJs.contains("Authorization"))
    }

    @Test
    fun `page no longer claims that every run is a simulation, and shows the kind and the self-development note`() {
        // The fixed sentence is gone: self-development runs are listed too (see the kind badges).
        assertFalse(indexHtml.contains("すべてシミュレーション"))
        assertFalse(indexHtml.contains("executed: false"))
        assertFalse(appJs.contains("executed"), "app.js must not display the executed flag")
        assertContains(indexHtml, "id=\"mode-note\"")
        // The banner text depends on GET /api/selfdev/status, and says nothing when the status is unknown.
        assertContains(appJs, "apiFetch('/api/selfdev/status', 'GET')")
        assertContains(appJs, "自己開発の Run は、GitHub に PR を作成します")
        assertContains(appJs, "自己開発は無効です")
        assertContains(appJs, "自己開発の設定を取得できていません")
        // Kind badges: the two fixed kinds, and anything else is shown as unknown.
        assertContains(appJs, "SIMULATED: { label: 'シミュレーション'")
        assertContains(appJs, "SELF_DEV: { label: '自己開発（PR を作成）'")
        assertContains(appJs, "不明な種別")
        // Never claim that a Run ran.
        assertFalse(indexHtml.contains("実行中"))
        assertFalse(appJs.contains("実行中"))
    }

    @Test
    fun `the autopilot sections are hidden in the page, and only a 200 that says enabled shows them`() {
        assertContains(indexHtml, "id=\"goal-section\" aria-labelledby=\"h-goal\" hidden")
        assertContains(indexHtml, "id=\"plans-section\" aria-labelledby=\"h-plans\" hidden")
        assertContains(appJs, "apiFetch('/api/selfdev/autopilot', 'GET')")
        assertContains(appJs, "r.ok && r.data && r.data.enabled === true")
        assertContains(appJs, "$('goal-section').hidden = !on;")
        assertContains(appJs, "apiFetch('/api/selfdev/goals', 'POST', body)")
        assertContains(appJs, "apiFetch('/api/selfdev/goals', 'GET')")
    }

    @Test
    fun `operators can switch between their own runs and all runs, and the detail keeps the scope`() {
        assertContains(indexHtml, "id=\"scope-form\"")
        assertContains(indexHtml, "id=\"scope-mine\"")
        assertContains(indexHtml, "id=\"scope-all\"")
        assertContains(appJs, "apiFetch('/api/admin/runs?limit=100', 'GET')")
        assertContains(appJs, "apiFetch('/api/runs', 'GET')")
        assertContains(appJs, "apiFetch('/api/admin/runs/' + encodeURIComponent(id), 'GET')")
        assertContains(appJs, "scope=all")
        // A response for a scope that is no longer selected is dropped.
        assertContains(appJs, "if (scope !== state.scope) return;")
    }

    @Test
    fun `titles are shown as text for both kinds, and an unknown kind shows no title`() {
        assertContains(appJs, "if (run.mode === 'SIMULATED' || run.mode === 'SELF_DEV') return clip(run.title, MAX_TITLE);")
        assertContains(appJs, "（非表示：種別が不明）")
        assertFalse(appJs.contains("（非表示：自己開発）"), "self-development titles are shown (ADR 0008 addendum)")
        // The title is only ever written with textContent (through h()'s text prop), never as markup.
        assertContains(appJs, "h('span', { className: 'run-title', text: titleOf(run) })")
        // Every value from the server goes into the DOM as text: no markup is ever built from strings.
        assertFalse(Regex("\\.(textContent|innerText)\\s*=\\s*[^;]*\\+\\s*'<").containsMatchIn(appJs))
        assertFalse(Regex("<[a-z]+[^>]*\\$\\{").containsMatchIn(appJs))
    }

    @Test
    fun `a pull request is a link only when its url has the exact github shape, otherwise it is text`() {
        // The only href that comes from the server is checked by safePrUrl(); the list link is a fixed path.
        assertContains(appJs, "var PR_URL = /^https:\\/\\/github\\.com\\/[A-Za-z0-9_.-]+\\/[A-Za-z0-9_.-]+\\/pull\\/[0-9]+$/;")
        assertContains(appJs, "return typeof url === 'string' && PR_URL.test(url) ? url : null;")
        assertContains(appJs, "var url = safePrUrl(run.pullRequestUrl);")
        assertContains(appJs, "if (url === null) return h('span', { text: label });")
        assertContains(appJs, "h('a', { href: url, target: '_blank', rel: 'noopener noreferrer', text: label + ' ↗' })")
        // Exactly three href sites: the list link (fixed path plus an encoded id), the checked PR link, and the project sidebar link (fixed '#/').
        assertEquals(3, Regex("\\bhref:").findAll(appJs).count(), "href sites")
        assertContains(appJs, "href: '#/runs/' + encodeURIComponent(id)")
        // target and rel are assigned only from constants, through the allow-listed prop names.
        assertContains(appJs, "else if (k === 'target' || k === 'rel') node[k] = v;")
    }

    @Test
    fun `reason codes and states are looked up by own keys only, and unknown values are shown as they are`() {
        // Object lookups use hasOwnProperty, so a code such as "constructor" is not a known reason.
        assertContains(appJs, "return Object.prototype.hasOwnProperty.call(map, key);")
        assertContains(appJs, "if (has(REASONS, c)) return REASONS[c] + '（' + c + '）';")
        assertContains(appJs, "if (has(STATES, c)) return { label: c, desc: STATES[c] };")
        // Unknown states and reasons are shown as text, never as an empty or fabricated explanation.
        assertContains(appJs, "不明な状態です")
        assertContains(appJs, "不明な理由（")
    }

    @Test
    fun `the three coding codes a run can end with have their own explanation`() {
        // The explanation lines of Issue #231 item 4. reasonText still shows the code after the text.
        assertContains(appJs, "CODING_EMPTY_PATCH: '変更が無く終了しました'")
        assertContains(appJs, "CODING_BUDGET_EXCEEDED: '1 attempt の費用上限に達しました'")
        assertContains(appJs, "CODING_OVERLOADED: 'モデルが混雑していました'")
    }

    @Test
    fun `the site root redirects to the ui with a fixed relative location`() = testApplication {
        application { routing { configureUiRoutes() } }
        val noFollow = createClient { followRedirects = false }

        val response = noFollow.get("/")
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/ui", response.headers["Location"])
        assertEquals("no-store", response.headers["Cache-Control"])
        // A query string must not change where it goes.
        assertEquals("/ui", noFollow.get("/?next=https://evil.example/").headers["Location"])
    }

    @Test
    fun `the site root is not registered without KIVRA_AUTH_MODE`() = testApplication {
        environment { config = h2Config() }
        application { appModule(emptyMap()) }

        assertEquals(HttpStatusCode.NotFound, client.get("/").status)
    }

    @Test
    fun `expired session is detected from 401, opaque redirects and non-json responses`() {
        // oauth2-proxy answers an expired session with 401 or a redirect to the sign-in page.
        assertContains(appJs, "401")
        assertContains(appJs, "redirect: 'manual'")
        assertContains(appJs, "'opaqueredirect'")
        assertContains(appJs, "application\\/json")
        assertContains(appJs, "markSessionExpired")
        // The automatic run-list refresh stops after an expired session.
        assertContains(appJs, "state.sessionExpired) return")
    }

    @Test
    fun `expired session guards task submission, run detail and apiFetch without network access`() {
        // Submit: the button is disabled through the single updateSubmitEnabled() and the handler returns early.
        assertContains(appJs, "function updateSubmitEnabled()")
        assertContains(appJs, "state.submitting || state.sessionExpired")
        assertContains(appJs, "if (state.submitting || state.sessionExpired) {")
        // The send's finally re-evaluates the button, so it cannot re-enable it after the session expired.
        assertContains(appJs, "} finally {\n      state.submitting = false;\n      updateSubmitEnabled();")
        assertContains(appJs, "\$('submit-note').hidden = !state.sessionExpired;")
        assertContains(indexHtml, "id=\"submit-note\"")
        // Run detail: no /api/runs/{id} request after expiry.
        assertContains(appJs, "if (state.sessionExpired) {\n      body.appendChild(h('p', { className: 'error', text: MESSAGES.session }));")
        // apiFetch: /api/* is answered locally with kind 'session'; /health is not guarded.
        assertContains(appJs, "if (isApi(path) && state.sessionExpired) return { ok: false, kind: 'session' };")
        // The guard is a request-reduction only and says so in the code.
        assertContains(appJs, "Client-side guard only")
        // The page never clears sessionExpired itself; only a reload (new page state) does.
        assertEquals(1, Regex("state\\.sessionExpired\\s*=").findAll(appJs).count(), "only markSessionExpired sets it")
        assertFalse(Regex("\\.href\\s*=[^=]").containsMatchIn(appJs), "app.js must not assign .href")
    }

    @Test
    fun `reload button is the only way back to the sign-in flow`() {
        // The sign-in redirect is done by oauth2-proxy on reload; the page never builds a redirect target.
        assertContains(appJs, "window.location.reload()")
        for (token in listOf("location.href =", "location.href=", "location.assign", "location.replace", "window.open")) {
            assertFalse(appJs.contains(token), "app.js must not use $token")
        }
        assertContains(indexHtml, "id=\"reload-btn\"")
    }

    @Test
    fun `session expired banner exists in the page and is hidden until needed`() {
        assertContains(indexHtml, "id=\"session-banner\"")
        assertContains(indexHtml, "role=\"alert\"")
        assertContains(indexHtml, "セッションが切れました（ログインが必要です）")
        assertTrue(Regex("id=\"session-banner\"[^>]*\\shidden").containsMatchIn(indexHtml))
        assertContains(appJs, "\$('session-banner').hidden = false")
        assertContains(appCss, ".session-banner")
    }

    @Test
    fun `title input is limited to 200 characters`() {
        assertContains(indexHtml, "maxlength=\"200\"")
    }

    @Test
    fun `ui is not registered without KIVRA_AUTH_MODE`() = testApplication {
        environment { config = h2Config() }
        application { appModule(emptyMap()) }

        assertEquals(HttpStatusCode.NotFound, client.get("/ui").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/ui/app.js").status)
    }

    @Test
    fun `ui is registered when the task api is enabled in development`() = testApplication {
        environment { config = h2Config() }
        application {
            appModule(mapOf("KIVRA_AUTH_MODE" to "none", "KIVRA_RUNTIME_ENV" to "development"))
        }

        val response = client.get("/ui")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("text/html; charset=utf-8", response.headers["Content-Type"])
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    @Test
    fun `the kind selector and the release dialog are in the page, with no inline handlers`() {
        assertContains(indexHtml, "id=\"kind-form\"")
        assertContains(indexHtml, "id=\"kind-sim\"")
        assertContains(indexHtml, "id=\"kind-self\"")
        assertContains(indexHtml, "value=\"SELF_DEV\"")
        assertContains(indexHtml, "<dialog id=\"release-dialog\"")
        assertContains(indexHtml, "id=\"release-reason\"")
        assertContains(indexHtml, "id=\"release-submit\"")
        assertContains(indexHtml, "id=\"release-cancel\"")
        // CSP blocks inline event handlers; every handler is attached from app.js.
        assertFalse(Regex("\\son[a-z]+\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(indexHtml), "no inline event handler attributes")
        assertFalse(indexHtml.contains("<form id=\"release"), "the release dialog is not a form (Enter must not submit)")
    }

    @Test
    fun `self-development submit and release calls go through apiFetch with fixed paths and a JSON body`() {
        // Kinds: the self-development submit is only reached through the kind check, and both paths are literals.
        assertContains(appJs, "apiFetch('/api/selfdev/tasks', 'POST', body)")
        assertContains(appJs, "apiFetch('/api/tasks', 'POST', body)")
        assertContains(appJs, "apiFetch('/api/admin/selfdev/runs/' + encodeURIComponent(rel.runId) + '/release', 'POST', { reason: reason })")
        assertContains(
            appJs,
            "apiFetch('/api/admin/selfdev/runs/' + encodeURIComponent(rel.runId) + '/release-unbound-pr', 'POST', { reason: reason })",
        )
        // apiFetch is the only place that sends a body, and it always sets the JSON content type (CSRF guard, #193).
        assertContains(appJs, "init.headers['Content-Type'] = 'application/json';")
        assertFalse(Regex("\\bfetch\\(").findAll(appJs).count() > 1, "fetch is called only from apiFetch")
        // The idempotency key is never sent with an empty value and a kind change makes a new key.
        assertContains(appJs, "newSubmitKey();")
        assertContains(appJs, "if (state.keyFor !== null) state.idempotencyKey = newKey();")
    }

    @Test
    fun `release and submit texts are fixed, and only text nodes receive server data in the new code`() {
        // The response body is never shown: the messages are constants and the result is set from them.
        assertContains(appJs, "showReleaseError(msg);")
        assertContains(appJs, "showResult(msg, 'error');")
        assertFalse(appJs.contains("r.data.error"), "no error body is shown")
        assertContains(appJs, "$('release-explain').textContent = text.explain;")
        assertContains(appJs, "$('release-title').textContent = text.title;")
        assertContains(appJs, "$('detail-notice').textContent = MESSAGES.releaseDone;")
        // The reason is only read from the input (value), never written into markup.
        assertFalse(Regex("release-reason'\\)\\.value\\s*\\+").containsMatchIn(appJs))
    }

    private fun h2Config() = MapApplicationConfig(
        "kivra.database.jdbcUrl" to "jdbc:h2:mem:kivra_ui_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "kivra.database.user" to "sa",
        "kivra.database.password" to "",
        "kivra.lease.ttlSeconds" to "30",
        "kivra.lease.graceSeconds" to "5",
    )
}
